package com.example.byokchat.tools

import com.example.byokchat.youtube.YouTubeExtractor

/**
 * Shared helpers for the media download tools (soundcloud_download —
 * same shape as youtube_download).
 * YouTubeTools keeps its own private copies; these exist so the new
 * per-source tools share one implementation instead of a third copy.
 */

/** Folder target (app names the file) or full file path -> the final file path. */
internal fun resolveTargetFile(env: ToolEnv, rawPath: String, fileName: String): String {
    val trimmed = rawPath.trimEnd('/')
    val last = trimmed.substringAfterLast('/')
    val isFolder = rawPath.endsWith("/") ||
        runCatching { env.fs.find(rawPath) }.getOrNull()?.let { env.fs.isDir(it) } == true ||
        !last.contains('.')
    val finalPath = if (isFolder) "$trimmed/$fileName" else trimmed
    // The final file must sit inside a granted folder (never at the virtual root).
    env.fs.checkOp(PlaygroundOp.WRITE_FILE, finalPath)
    return finalPath
}

internal suspend fun downloadStreamTo(env: ToolEnv, url: String, target: String) {
    val segs = env.fs.segments(target)
    val root = env.fs.rootForPath(target)
    val parent = env.fs.ensureDir(segs.drop(1).dropLast(1), root = root.requireDoc())
    val name = segs.last()
    var doc = env.fs.childByName(parent, name)
    if (doc != null && env.fs.isDir(doc)) throw ToolException("'$target' is a folder.")
    if (doc == null) doc = env.fs.createFileNamed(parent, name)
    // Live progress for the tool card ("Running. Progress 42%"): reported
    // through the call's progress scope, never into the result text.
    env.fs.openOut(doc).use { out ->
        YouTubeExtractor.downloadTo(url, out) { downloaded, total ->
            env.reportProgress(downloaded, total)
        }
    }
}
