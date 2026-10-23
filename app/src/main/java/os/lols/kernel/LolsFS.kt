package os.lols.kernel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.lols.lols.LolsError
import os.lols.lols.Runtime
import java.io.File

/** One entry of a directory listing. */
data class FsEntry(val name: String, val size: Long, val dir: Boolean)

/**
 * LolsFS: the real persistent filesystem of LOLS OS.
 * It lives inside the app's private storage, survives reboots, enforces a
 * real 64MB disk quota, and backs everything: installed apps, settings,
 * security data and user files.
 */
class LolsFS(context: Context) {

    private val root: File = File(context.filesDir, "lolfs")

    companion object {
        const val QUOTA_BYTES: Long = 64L * 1024 * 1024
        val BOOT_DIRS = listOf("/sys", "/sys/packages", "/sys/security", "/sys/settings", "/apps", "/home", "/data")
    }

    /** Mount: create the standard tree if missing and report real stats. */
    suspend fun mount(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        root.mkdirs()
        for (d in BOOT_DIRS) {
            val f = resolve(d)
            if (!f.exists()) f.mkdirs()
        }
        countAll()
    }

    fun resolve(path: String): File {
        val norm = Runtime.normalize(path)
        val f = File(root, norm)
        val canon = f.canonicalFile
        if (!canon.path.startsWith(root.canonicalFile.path)) {
            throw LolsError("path escapes the filesystem: $path")
        }
        return canon
    }

    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        resolve(path).exists()
    }

    suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        val f = resolve(path)
        f.isDirectory || f.mkdirs()
    }

    suspend fun read(path: String): String = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (f.isFile) f.readText() else ""
    }

    suspend fun write(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        val projected = usedBytesBlocking() + content.length - (if (f.isFile) f.length() else 0L)
        if (projected > QUOTA_BYTES) return@withContext false
        try { f.writeText(content); true } catch (e: Exception) { false }
    }

    suspend fun append(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        if (usedBytesBlocking() + content.length > QUOTA_BYTES) return@withContext false
        try { f.appendText(content); true } catch (e: Exception) { false }
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.exists()) return@withContext false
        if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.isDirectory) return@withContext emptyList()
        val out = ArrayList<FsEntry>()
        if (Runtime.normalize(path) != "/") out.add(FsEntry("..", 0, true))
        val children = f.listFiles()?.toList() ?: emptyList()
        val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        for (c in sorted) out.add(FsEntry(c.name, if (c.isDirectory) 0 else c.length(), c.isDirectory))
        out
    }

    /** Recursive disk usage of a path. */
    suspend fun du(path: String): Long = withContext(Dispatchers.IO) {
        resolve(path).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /** Count of files + total bytes in the whole filesystem. */
    suspend fun countAll(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        var n = 0
        var bytes = 0L
        root.walkBottomUp().forEach {
            if (it.isFile) { n++; bytes += it.length() }
        }
        n to bytes
    }

    private fun usedBytesBlocking(): Long {
        var bytes = 0L
        root.walkBottomUp().forEach { if (it.isFile) bytes += it.length() }
        return bytes
    }

    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { usedBytesBlocking() }
}
