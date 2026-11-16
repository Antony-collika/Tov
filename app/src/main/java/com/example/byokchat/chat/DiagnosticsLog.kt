package com.example.byokchat.chat

import java.io.File
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

    /** One timestamped line: "2026-11-16 09:12:33.123 [tag] message". Never throws. */
    fun log(tag: String, message: String) {
        runCatching {
            synchronized(lock) {
                dir.mkdirs()
                val f = file()
                f.appendText("${timeFormat.format(Date())} [$tag] $message\n")
                if (f.length() > maxBytes) trimLocked(f)
            }
        }
    }

    /** One line for a delegate handoff step. */
    fun handoff(step: String, conversationId: String, turnId: String, detail: String = "") {
        log("handoff/$step", "conv=${conversationId.take(8)} turn=$turnId $detail".trim())
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
