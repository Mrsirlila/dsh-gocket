package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Direct root invocation: MethodChannel `dsh/root`.
 *
 * Gated by an opt-in flag persisted in shared preferences, so a call to
 * `exec` is refused natively until the person turned the switch on.
 *
 * - `available` → probes `su -c id` (the true capability check; on a rooted
 *   device the manager may show its own grant prompt the first time).
 * - `isEnabled` / `setEnabled {enabled}` → the gate flag.
 * - `exec {command}` → `su -c` the command, returning `{code, output}`; merges
 *   stderr so the caller sees why it failed.
 */
class RootPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    companion object {
        const val CHANNEL = "dsh/root"
        private const val PREFS = "dsh_root"
        private const val KEY_ENABLED = "enabled"
        private const val TIMEOUT_SECONDS = 60L
    }

    private lateinit var context: Context
    private lateinit var methods: MethodChannel
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, CHANNEL).also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods.setMethodCallHandler(null)
    }

    private fun enabled(): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** `su` resolves through PATH for app processes; an `su -c id` probe is the real check. */
    private fun suAvailable(): Boolean = try {
        ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
    } catch (_: Exception) {
        false
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "available" -> {
                worker.execute {
                    val ok = suAvailable()
                    main.post { result.success(ok) }
                }
            }
            "isEnabled" -> result.success(enabled())
            "setEnabled" -> {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, call.argument<Boolean>("enabled") == true).apply()
                result.success(null)
            }
            "exec" -> {
                if (!enabled()) {
                    result.error("disabled", "root access is off", null)
                    return
                }
                val command = call.argument<String>("command")
                if (command.isNullOrBlank()) {
                    result.error("bad_arg", "command is empty", null)
                    return
                }
                worker.execute {
                    val out = runCatching { exec(command) }
                    main.post {
                        out.onSuccess { result.success(it) }
                            .onFailure { result.error("exec_failed", it.message, null) }
                    }
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun exec(command: String): Map<String, Any> {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("command timed out after ${TIMEOUT_SECONDS}s")
        }
        return mapOf("code" to process.exitValue(), "output" to output)
    }
}
