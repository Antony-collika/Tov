package com.example.byokchat.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic log (16/11/2026): timestamped lines for the delegate
 * handoff (sub run → delegate tool → engine upsert → next request), so a
 * stuck turn can be diagnosed from the device instead of guessed at.
 *
 * One rolling text file (diagnostics.log) in the app's files dir, capped
 * so it cannot grow forever: when it passes [MAX_BYTES], the oldest half
 * of the lines is dropped. Plain text, so the Diagnostics screen can show
 * it and the user can copy or export it. Best-effort: a write failure
 * never breaks the chat. Plain (non-suspend) calls, like RequestLog.
 */
class DiagnosticsLog(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
) {
    companion object {
        const val FILE_NAME = "diagnostics.log"
        /** Keep the file under this size; a trim drops the oldest half of the lines. */
        const val MAX_BYTES = 512L * 1024
    }

    private val lock = Any()
    // SimpleDateFormat is not thread-safe; every use is inside synchronized(lock).
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun file(): File = File(dir, FILE_NAME)

    /** The whole log text (empty when there is no file yet). Never throws. */
    fun readText(): String = runCatching { file().readText() }.getOrDefault("")

    /** One timestamped line: "2026-11-16 09:12:33.123 [tag] message thread=main". Never throws. */
    fun log(tag: String, message: String) {
        runCatching {
            synchronized(lock) {
                dir.mkdirs()
                val f = file()
                f.appendText("${timeFormat.format(Date())} [$tag] $message thread=${Thread.currentThread().name}\n")
                if (f.length() > maxBytes) trimLocked(f)
            }
        }
    }

    /** One line for a delegate handoff step. */
    fun handoff(step: String, conversationId: String, turnId: String, detail: String = "") {
        log("handoff/$step", "conv=${conversationId.take(8)} turn=$turnId $detail".trim())
    }

    /**
     * Thread dump on demand (16/11/2026): capture every thread's name,
     * state and stack trace and append it to the log as one delimited
     * block, so a stuck turn can be diagnosed from the exact threads
     * instead of guessed at. Called only when the user taps "Thread dump"
     * on the Diagnostics screen — no background work, no watcher, no
     * polling. Best-effort like [log]: a failure never breaks the chat.
     */
    fun threadDump() {
        runCatching {
            val traces = Thread.getAllStackTraces()
            synchronized(lock) {
                dir.mkdirs()
                val f = file()
                f.appendText(formatThreadDump(traces, timeFormat.format(Date())))
                if (f.length() > maxBytes) trimLocked(f)
            }
        }
    }

    /**
     * Coroutine dump on demand (16/11/2026): dump every live coroutine's
     * state and last suspension point and append it to the log as one
     * delimited block, in the same style as [threadDump]. A thread dump
     * cannot show a suspended coroutine (it has no thread and no stack),
     * so this is what shows where a stuck turn is parked. Needs the debug
     * probes installed at startup (see App); when they are not installed,
     * [DebugProbes.dumpCoroutines] throws, so the block carries a note
     * instead. Called only when the user taps "Coroutine dump" on the
     * Diagnostics screen — no background work, no watcher, no polling.
     * Best-effort like [log]: a failure never breaks the chat.
     *
     * Returns true only when a real dump was written. A failure is VISIBLE
     * instead of silent: on device the release build's R8 broke
     * [DebugProbes.dumpCoroutines] (16/11/2026) and the button showed
     * "saved" while nothing was written. On any exception a failure block
     * with the exception is appended to the log instead, and the result is
     * false so the screen can say so.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun coroutineDump(): Boolean {
        return try {
            val installed = DebugProbes.isInstalled
            val dump = if (installed) {
                val bytes = ByteArrayOutputStream()
                PrintStream(bytes, true, Charsets.UTF_8).use { DebugProbes.dumpCoroutines(it) }
                bytes.toString(Charsets.UTF_8.name())
            } else {
                ""
            }
            synchronized(lock) {
                dir.mkdirs()
                val f = file()
                f.appendText(formatCoroutineDump(dump, installed, timeFormat.format(Date())))
                if (f.length() > maxBytes) trimLocked(f)
            }
            installed && dump.isNotBlank()
        } catch (e: Throwable) {
            // The dump itself failed: write the failure into the log so the
            // next export shows what broke, instead of a toast claiming
            // success. Still best-effort: if even that write fails, swallow
            // it — the chat must not break.
            runCatching {
                synchronized(lock) {
                    dir.mkdirs()
                    val f = file()
                    f.appendText(formatCoroutineDumpFailure(e, timeFormat.format(Date())))
                    if (f.length() > maxBytes) trimLocked(f)
                }
            }
            false
        }
    }

    /**
     * Clear the log (16/11/2026): delete diagnostics.log so a new test run
     * starts from an empty log instead of mixing sessions. Only this file
     * is touched; the next [log]/[threadDump]/[coroutineDump] recreates it
     * (each write does mkdirs + appendText). Called only when the user taps
     * "Clear" on the Diagnostics screen. Best-effort like [log].
     */
    fun clear() {
        runCatching {
            synchronized(lock) {
                val f = file()
                if (f.exists()) f.delete()
            }
        }
    }

    private fun trimLocked(f: File) {
        val lines = f.readLines()
        if (lines.isEmpty()) return
        val dropped = lines.size / 2
        val kept = lines.drop(dropped)
        f.writeText(
            (kept + "[trimmed $dropped old lines to stay under ${maxBytes / 1024} KB]")
                .joinToString("\n") + "\n",
        )
    }
}

/**
 * One delimited thread-dump block, ready to append to the log. Pure, so
 * it can be unit-tested. Threads are sorted by name so two dumps of the
 * same state read the same; a thread with no frames gets "(no stack trace)"
 * instead of an empty block.
 */
internal fun formatThreadDump(traces: Map<Thread, Array<StackTraceElement>>, timestamp: String): String =
    buildString {
        appendLine("$timestamp [thread-dump] --- thread dump: ${traces.size} threads ---")
        traces.entries.sortedBy { it.key.name }.forEach { (thread, frames) ->
            appendLine("\"${thread.name}\" state=${thread.state} daemon=${thread.isDaemon}")
            if (frames.isEmpty()) {
                appendLine("    (no stack trace)")
            } else {
                frames.forEach { appendLine("    at $it") }
            }
        }
        appendLine("--- end thread dump ---")
    }

/**
 * One delimited coroutine-dump failure block, ready to append to the log.
 * Pure, so it can be unit-tested. The dump call itself can throw (on
 * device the release build's R8 broke DebugProbes.dumpCoroutines) — the
 * failure must be visible in the log, with the exception and its first
 * frames, instead of a toast claiming the dump was saved.
 */
internal fun formatCoroutineDumpFailure(e: Throwable, timestamp: String): String =
    buildString {
        appendLine("$timestamp [coroutine-dump] --- coroutine dump FAILED ---")
        appendLine("    ${e.javaClass.name}: ${e.message}")
        e.stackTrace.take(10).forEach { appendLine("    at $it") }
        appendLine("--- end coroutine dump ---")
    }

/**
 * One delimited coroutine-dump block, ready to append to the log. Pure, so
 * it can be unit-tested. When the probes are not installed there is no
 * dump text, so the block carries a note instead of staying empty; when
 * they are installed but no coroutine exists, it says so.
 */
internal fun formatCoroutineDump(dump: String, installed: Boolean, timestamp: String): String =
    buildString {
        appendLine("$timestamp [coroutine-dump] --- coroutine dump ---")
        if (!installed) {
            appendLine("    (DebugProbes not installed — coroutine dump unavailable)")
        } else if (dump.isBlank()) {
            appendLine("    (no coroutines)")
        } else {
            append(dump)
            if (!dump.endsWith("\n")) appendLine()
        }
        appendLine("--- end coroutine dump ---")
    }
