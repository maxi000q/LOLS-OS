package os.lols.kernel

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.lols.lols.Lexer
import os.lols.lols.LolsError
import os.lols.lols.Parser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** A package installed in /apps (or a bundled system app). */
data class AppInfo(
    val id: String,
    val name: String,
    val version: String,
    val icon: String,
    val color: String,
    val perms: List<String>,
    val system: Boolean,
    val entry: String, // "lols" or "native:store" ...
)

/** An app available in the bundled repo (assets/repo/*.lols). */
data class RepoEntry(
    val id: String,
    val name: String,
    val version: String,
    val icon: String,
    val color: String,
    val sizeBytes: Long,
)

/**
 * Real package manager: .lols files are ZIP containers (manifest.lols +
 * main.lols). Installing means parsing, validating, extracting real files
 * into /apps/<id>/ and updating a persistent registry.
 */
class PackageManager(private val kernel: Kernel, private val ctx: Context) {

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps = _apps.asStateFlow()

    private val registryPath = "/sys/packages/registry.txt"
    private val removedPath = "/sys/packages/removed.txt"
    private val bundlePath = "/sys/settings/bundle"

    companion object {
        const val BUNDLE_TAG = "lols-bundle-v1"

        val NATIVES = listOf(
            AppInfo("os.lols.store", "Store", "1.0.0", "\u25C6", "#34D399", emptyList(), true, "native:store"),
            AppInfo("os.lols.settings", "Settings", "1.0.0", "\u2699", "#94A3B8", emptyList(), true, "native:settings"),
            AppInfo("os.lols.terminal", "Terminal", "1.0.0", "\u276F", "#4ADE80", listOf("fs.full"), true, "native:terminal"),
        )
    }

    // ---------------- registry ----------------

    suspend fun loadRegistry() {
        val text = kernel.fs.read(registryPath)
        val list = ArrayList<AppInfo>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val p = line.split("|")
            if (p.size < 8) continue
            list.add(
                AppInfo(
                    id = p[0], name = p[1], version = p[2], icon = p[3], color = p[4],
                    perms = p[5].split(",").filter { it.isNotBlank() },
                    system = p[6] == "1",
                    entry = p[7],
                )
            )
        }
        _apps.value = list.sortedBy { it.name }
    }

    private suspend fun saveRegistry(list: List<AppInfo>) {
        val text = list.joinToString("\n") {
            "${it.id}|${it.name}|${it.version}|${it.icon}|${it.color}|${it.perms.joinToString(",")}|${if (it.system) 1 else 0}|${it.entry}"
        }
        kernel.fs.write(registryPath, text)
    }

    fun permsOf(appId: String): List<String> =
        _apps.value.firstOrNull { it.id == appId }?.perms ?: emptyList()

    fun byId(appId: String): AppInfo? = _apps.value.firstOrNull { it.id == appId }

    // ---------------- install / uninstall ----------------

    /**
     * Install a .lols ZIP: parse manifest, fully validate main.lols
     * (a real parse - broken packages are rejected), extract into
     * /apps/<id>/ and register.
     */
    suspend fun installFromZip(bytes: ByteArray): AppInfo {
        var manifest: String? = null
        var main: String? = null
        withContext(Dispatchers.IO) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "manifest.lols" -> manifest = zip.readBytes().toString(Charsets.UTF_8)
                        "main.lols" -> main = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val man = manifest ?: throw LolsError("package missing manifest.lols")
        val code = main ?: throw LolsError("package missing main.lols")

        val decl = try {
            Parser(Lexer(man).lex()).parseProgram().app
        } catch (e: LolsError) {
            throw LolsError("bad manifest: ${e.message}")
        }
        val id = decl.metas["id"] ?: throw LolsError("manifest missing id")
        if (!id.matches(Regex("[a-zA-Z0-9_.]+"))) throw LolsError("bad package id: $id")
        // real validation: the app code must parse
        try {
            Parser(Lexer(code).lex()).parseProgram()
        } catch (e: LolsError) {
            throw LolsError("main.lols does not compile: ${e.message}")
        }

        val perms = (decl.metas["permission"] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
        val app = AppInfo(
            id = id,
            name = decl.metas["name"] ?: decl.name,
            version = decl.metas["version"] ?: "1.0.0",
            icon = decl.metas["icon"] ?: "\u25CE",
            color = decl.metas["color"] ?: "#6EE7D8",
            perms = perms,
            system = false,
            entry = "lols",
        )

        kernel.fs.mkdir("/apps/$id")
        if (!kernel.fs.write("/apps/$id/manifest.lols", man)) throw LolsError("disk write failed")
        if (!kernel.fs.write("/apps/$id/main.lols", code)) throw LolsError("disk write failed")

        val list = _apps.value.filter { it.id != id } + app
        _apps.value = list.sortedBy { it.name }
        saveRegistry(_apps.value)
        // reinstalling clears the "user removed" flag
        val removed = kernel.fs.read(removedPath)
        if (removed.contains(id)) {
            kernel.fs.write(removedPath, removed.lines().filter { it.trim() != id }.joinToString("\n"))
        }
        kernel.logLine("[pkg] installed $id v${app.version}")
        return app
    }

    suspend fun uninstall(id: String) {
        val app = byId(id) ?: return
        if (app.system) throw LolsError("system apps cannot be uninstalled")
        kernel.processes.findByAppAny(id)?.let { kernel.processes.kill(it.pid, "uninstalled") }
        kernel.fs.delete("/apps/$id")
        _apps.value = _apps.value.filterNot { it.id == id }
        saveRegistry(_apps.value)
        kernel.fs.append(removedPath, "$id\n")
        kernel.logLine("[pkg] uninstalled $id")
        kernel.bus.post("os.lols", app.name, kernel.t("uninstalled", "تم إلغاء التثبيت"))
    }

    // ---------------- bundled repo ----------------

    /** On first boot (or bundle upgrade) install missing bundled apps. */
    suspend fun ensureBundled(firstBoot: Boolean) {
        if (firstBoot) {
            // register native system apps
            if (_apps.value.none { it.entry.startsWith("native:") }) {
                _apps.value = (NATIVES + _apps.value).sortedBy { it.name }
                saveRegistry(_apps.value)
                kernel.logLine("[pkg] registered ${NATIVES.size} system apps")
            }
        }
        val current = kernel.fs.read(bundlePath)
        if (current == BUNDLE_TAG && !firstBoot) return
        val removed = kernel.fs.read(removedPath).lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        var installed = 0
        for (entry in repoList()) {
            if (byId(entry.id) == null && !removed.contains(entry.id)) {
                try {
                    val bytes = withContext(Dispatchers.IO) { ctx.assets.open("repo/${entry.id}.lols").readBytes() }
                    installFromZip(bytes)
                    installed++
                } catch (e: Exception) {
                    kernel.logLine("[pkg] bundled ${entry.id} failed: ${e.message}")
                }
            }
        }
        kernel.fs.write(bundlePath, BUNDLE_TAG)
        if (installed > 0) kernel.logLine("[pkg] installed $installed bundled app(s) from repo")
    }

    fun repoList(): List<RepoEntry> {
        val out = ArrayList<RepoEntry>()
        try {
            val names = ctx.assets.list("repo") ?: return emptyList()
            for (n in names.filter { it.endsWith(".lols") }) {
                try {
                    val bytes = ctx.assets.open("repo/$n").readBytes()
                    val meta = readMeta(bytes) ?: continue
                    out.add(RepoEntry(meta.id, meta.name, meta.version, meta.icon, meta.color, bytes.size.toLong()))
                } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
        return out.sortedBy { it.name }
    }

    private class ZipMeta(
        val id: String,
        val name: String,
        val version: String,
        val icon: String,
        val color: String,
    )

    private fun readMeta(bytes: ByteArray): ZipMeta? {
        var manifest: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "manifest.lols") manifest = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        val man = manifest ?: return null
        return try {
            val decl = Parser(Lexer(man).lex()).parseProgram().app
            val id = decl.metas["id"] ?: return null
            ZipMeta(
                id,
                decl.metas["name"] ?: decl.name,
                decl.metas["version"] ?: "1.0.0",
                decl.metas["icon"] ?: "\u25CE",
                decl.metas["color"] ?: "#6EE7D8",
            )
        } catch (e: Exception) { null }
    }

    suspend fun installFromRepo(id: String): AppInfo {
        val bytes = withContext(Dispatchers.IO) { ctx.assets.open("repo/$id.lols").readBytes() }
        return installFromZip(bytes)
    }

    private fun java.util.zip.ZipInputStream.readBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
