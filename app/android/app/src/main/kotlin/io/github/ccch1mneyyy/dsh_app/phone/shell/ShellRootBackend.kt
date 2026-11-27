package io.github.ccch1mneyyy.dsh_app.phone.shell

import android.content.Context
import java.io.File

/**
 * Root channel: the same stdin/stdout helper the self-ADB channel runs, started through `su`.
 * The person authorizes once in their root manager; no Shizuku app, wireless debugging or
 * accessibility service is involved. `su` is only spawned from [connect], never from [status].
 */
internal class ShellRootBackend(private val context: Context, private val onChanged: () -> Unit) {
    private val guard = Any()
    private var channel: ShellLink? = null
    private var busy = false
    private var generation = 0L

    fun status(): Map<String, Any?> = mapOf(
        "available" to available(),
        "connected" to (channel?.alive() == true),
    )

    fun connect(wanted: () -> Boolean): ShellConnection {
        channel?.takeIf { it.alive() && wanted() }?.let { return ShellConnection(it) }
        val ticket = begin() ?: return ShellConnection(errorCode = "busy")
        var candidate: ShellLink? = null
        var process: Process? = null
        var published = false
        return try {
            if (!available()) return ShellConnection(errorCode = "rootMissing")
            if (!wanted()) return ShellConnection(errorCode = "cancelled")
            val info = context.applicationInfo
            val classpath = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).joinToString(":")
            require(classpath.isNotBlank() && '\u0000' !in classpath && ' ' !in classpath)
            val quoted = "'" + classpath.replace("'", "'\\''") + "'"
            val command = "CLASSPATH=" + quoted + " exec /system/bin/app_process /system/bin " +
                AndroidShellMain::class.java.name
            process = ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
            // The root manager prints its own diagnostics on stderr; drain them or the child blocks.
            val drain = process.errorStream
            Thread({ runCatching { drain.use { stream -> stream.readBytes() } } },
                "dsh-shell-root-err").apply { isDaemon = true; start() }
            candidate = ShellLink.process(process)
            if (!candidate.probe()) return ShellConnection(errorCode = "rootDenied")
            synchronized(guard) {
                if (!valid(ticket) || !wanted()) return ShellConnection(errorCode = "cancelled")
                channel?.close()
                channel = candidate
                published = true
            }
            ShellConnection(candidate)
        } catch (_: Exception) {
            ShellConnection(errorCode = "connectFailed")
        } finally {
            if (!published) { candidate?.close(); runCatching { process?.destroy() } }
            finish(ticket); onChanged()
        }
    }

    fun disconnect() {
        synchronized(guard) {
            ++generation
            channel?.close()
            channel = null
        }
        onChanged()
    }

    /** `su` on PATH or where the common root managers put it. Read-only, never spawns `su`. */
    private fun available(): Boolean = SU_PATHS.any { File(it).canExecute() } ||
        runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", "command -v su").start()
            val found = process.inputStream.bufferedReader().readText().isNotBlank()
            process.waitFor()
            found
        }.getOrDefault(false)

    private fun begin(): Long? = synchronized(guard) {
        if (busy) null else { busy = true; generation }
    }

    private fun valid(ticket: Long): Boolean = synchronized(guard) { generation == ticket }

    private fun finish(ticket: Long) {
        synchronized(guard) { if (generation == ticket) busy = false }
    }

    private companion object {
        val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
        )
    }
}
