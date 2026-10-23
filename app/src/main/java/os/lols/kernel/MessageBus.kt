package os.lols.kernel

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Inter-process messaging & notification center.
 * .lols apps post notifications via the notify() syscall; the shell shows
 * banners (unless Do-Not-Disturb), badges and the notification shade.
 */
class MessageBus(private val kernel: Kernel) {

    data class Notif(
        val id: Long,
        val appId: String,
        val title: String,
        val body: String,
        val timeMs: Long,
    )

    private val _notifs = MutableStateFlow<List<Notif>>(emptyList())
    val notifs = _notifs.asStateFlow()

    private val _banner = MutableSharedFlow<Notif>(extraBufferCapacity = 8)
    val banner = _banner.asSharedFlow()

    private var nextId = 1L

    fun post(appId: String, title: String, body: String) {
        val n = Notif(nextId++, appId, title, body, System.currentTimeMillis())
        _notifs.value = listOf(n) + _notifs.value
        if (_notifs.value.size > 50) _notifs.value = _notifs.value.take(50)
        if (!kernel.settings.value.dnd) {
            _banner.tryEmit(n)
        }
    }

    fun dismiss(id: Long) {
        _notifs.value = _notifs.value.filter { it.id != id }
    }

    fun clearAll() {
        _notifs.value = emptyList()
    }

    fun unreadFor(appId: String): Int = _notifs.value.count { it.appId == appId }
}
