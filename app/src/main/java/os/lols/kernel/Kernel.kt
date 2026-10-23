package os.lols.kernel

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** User-facing OS settings. Every field is persisted in /sys/settings. */
data class OsSettings(
    val langArabic: Boolean = false,
    val graphics: Int = 0,      // 0 = high, 1 = balanced, 2 = lite
    val wallpaper: Int = 0,     // 0 = nebula, 1 = ember, 2 = forest
    val ramMb: Int = 16,
    val watchdogS: Int = 5,
    val yieldSteps: Int = 512,
    val dnd: Boolean = false,
    val lockEnabled: Boolean = true,
)

/**
 * The LOLS kernel: service registry + settings + logging + boot orchestration.
 * One instance per app process, owned by LolsApplication.
 */
class Kernel private constructor(context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val fs = LolsFS(context)
    val mem = MemoryManager(this)
    val bus = MessageBus(this)
    val power = PowerBridge(context, scope)
    val processes = ProcessManager(this)
    val packages = PackageManager(this, context)

    private val _settings = MutableStateFlow(OsSettings())
    val settings = _settings.asStateFlow()

    private val _bootLog = MutableSharedFlow<String>(replay = 128, extraBufferCapacity = 128)
    val bootLog = _bootLog.asSharedFlow()

    private val _bootDone = MutableStateFlow(false)
    val bootDone = _bootDone.asStateFlow()

    private val logRing = ArrayDeque<String>()
    private var bootAtMs = 0L
    private var bootedOnce = false

    init {
        // live process table refresh for recents / terminal / settings
        scope.launch {
            while (isActive) {
                delay(1000)
                if (bootedOnce) processes.refresh()
            }
        }
    }

    fun logLine(line: String) {
        synchronized(logRing) {
            logRing.addLast(line)
            while (logRing.size > 400) logRing.removeFirst()
        }
        _bootLog.tryEmit(line)
    }

    fun logLines(): List<String> = synchronized(logRing) { logRing.toList() }

    fun uptimeMs(): Long = if (bootAtMs == 0L) 0 else System.currentTimeMillis() - bootAtMs

    fun t(en: String, ar: String): String = if (_settings.value.langArabic) ar else en

    /** Boot: run the real staged boot sequence. */
    fun boot() {
        _bootDone.value = false
        scope.launch {
            BootSequence(this@Kernel, context).run()
            bootAtMs = System.currentTimeMillis()
            bootedOnce = true
            _bootDone.value = true
        }
    }

    fun reboot() {
        processes.killAll()
        bus.clearAll()
        boot()
    }

    fun shutdown() {
        processes.killAll()
    }

    // ---------------- settings persistence ----------------

    suspend fun loadSettings() {
        val s = _settings.value
        _settings.value = OsSettings(
            langArabic = fs.read("/sys/settings/lang") == "ar",
            graphics = fs.read("/sys/settings/graphics").toIntOrNull() ?: s.graphics,
            wallpaper = fs.read("/sys/settings/wallpaper").toIntOrNull() ?: s.wallpaper,
            ramMb = fs.read("/sys/settings/ramMb").toIntOrNull() ?: s.ramMb,
            watchdogS = fs.read("/sys/settings/watchdogS").toIntOrNull() ?: s.watchdogS,
            yieldSteps = fs.read("/sys/settings/yieldSteps").toIntOrNull() ?: s.yieldSteps,
            dnd = fs.read("/sys/settings/dnd") == "1",
            lockEnabled = fs.read("/sys/settings/lockEnabled") != "0",
        )
    }

    fun update(mutator: (OsSettings) -> OsSettings) {
        val old = _settings.value
        val new = mutator(old)
        _settings.value = new
        scope.launch {
            if (old.langArabic != new.langArabic) fs.write("/sys/settings/lang", if (new.langArabic) "ar" else "en")
            if (old.graphics != new.graphics) fs.write("/sys/settings/graphics", "${new.graphics}")
            if (old.wallpaper != new.wallpaper) fs.write("/sys/settings/wallpaper", "${new.wallpaper}")
            if (old.ramMb != new.ramMb) fs.write("/sys/settings/ramMb", "${new.ramMb}")
            if (old.watchdogS != new.watchdogS) fs.write("/sys/settings/watchdogS", "${new.watchdogS}")
            if (old.yieldSteps != new.yieldSteps) fs.write("/sys/settings/yieldSteps", "${new.yieldSteps}")
            if (old.dnd != new.dnd) fs.write("/sys/settings/dnd", if (new.dnd) "1" else "0")
            if (old.lockEnabled != new.lockEnabled) fs.write("/sys/settings/lockEnabled", if (new.lockEnabled) "1" else "0")
        }
    }

    // ---------------- security (lock screen PIN) ----------------

    suspend fun hasPin(): Boolean = fs.read("/sys/security/pin").isNotBlank()

    suspend fun setPin(pin: String) {
        val salt = fs.read("/sys/security/salt").ifBlank { randomSalt() }
        fs.write("/sys/security/salt", salt)
        fs.write("/sys/security/pin", sha256(salt + pin))
    }

    suspend fun checkPin(pin: String): Boolean {
        val salt = fs.read("/sys/security/salt")
        val stored = fs.read("/sys/security/pin")
        return stored.isNotBlank() && sha256(salt + pin) == stored
    }

    companion object {
        const val KERNEL_VERSION = "1.0.0"
        const val LOLS_RUNTIME_VERSION = "1.0.0"
        const val OS_NAME = "LOLS OS"

        @Volatile
        private var instance: Kernel? = null

        fun init(context: Context): Kernel {
            return instance ?: synchronized(this) {
                instance ?: Kernel(context.applicationContext).also { instance = it }
            }
        }

        fun get(): Kernel = instance ?: error("Kernel not initialized")

        private fun randomSalt(): String {
            val chars = "abcdef0123456789"
            return (1..32).map { chars[(0 until chars.length).random()] }.joinToString("")
        }

        fun sha256(s: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
