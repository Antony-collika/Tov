package com.example.byokchat.chat

/**
 * Agent config as a markdown file — the external form of [SubAgentConfig].
 *
 * Format (same section style as tools.md — one `## Heading` per field):
 *
 * ```
 * ## Name
 * My Reviewer
 * ## Role
 * Review agent
 * ## Description
 * Reviews a draft and reports back.
 * ## System prompt
 * You are Review. ...
 * ## Workspace folder
 * Review
 * ## Tools
 * web.search
 * web.fetch
 * ## Model
 * provider: gemini
 * model: gemini-3.5-flash-lite
 * ## Max rounds
 * 10
 * ## Token budget
 * 32000
 * ## History
 * brief
 * ## Enabled
 * on
 * ```
 *
 * - Lines starting with `>` are notes and are ignored.
 * - Unknown sections are ignored, so the file can carry extra notes.
 * - Missing sections mean "leave that field as it is" — a partial file
 *   only fills the fields it has ([ParsedAgentConfig] keeps every field
 *   nullable for exactly that).
 * - `## Model` with no provider/model lines means "follow Main's model".
 *   A provider without a model (or the reverse) is ignored as a pair.
 * - `## History` takes `brief` or `history`; `## Enabled` takes `on` or
 *   `off` (also `true`/`false`). Anything else is ignored, never an
 *   error: a hand-edited file should not wipe a field by accident.
 *
 * Pure functions, so they can be unit-tested without Android.
 */

/** One agent config as read from a file: null = that section was absent (or unusable). */
data class ParsedAgentConfig(
    val name: String? = null,
    val role: String? = null,
    val description: String? = null,
    val systemPrompt: String? = null,
    val workspaceFolder: String? = null,
    val toolUids: List<String>? = null,
    val modelProviderId: String? = null,
    val model: String? = null,
    val roundCap: Int? = null,
    val tokenBudget: Int? = null,
    val historyVisibility: HistoryVisibility? = null,
    val enabled: Boolean? = null,
) {
    /**
     * True when at least one parsed field differs from [config] — the
     * sync check: the file only overwrites the fields when something
     * actually changed. Tool order does not count (the settings screen
     * keeps tools as a set). The model only counts when both halves are
     * present (a lone provider/model line is ignored on merge).
     */
    fun differsFrom(config: SubAgentConfig): Boolean {
        if (name != null && name != config.name) return true
        if (role != null && role != config.role) return true
        if (description != null && description != config.description) return true
        if (systemPrompt != null && systemPrompt != config.systemPrompt) return true
        if (workspaceFolder != null && workspaceFolder != config.workspaceFolder) return true
        if (toolUids != null && toolUids.toSet() != config.toolUids.toSet()) return true
        if (modelProviderId != null && model != null) {
            if (modelProviderId != config.modelProviderId || model != config.model) return true
        }
        if (roundCap != null && roundCap != config.roundCap) return true
        if (tokenBudget != null && tokenBudget != config.tokenBudget) return true
        if (historyVisibility != null && historyVisibility != config.historyVisibility) return true
        if (enabled != null && enabled != config.enabled) return true
        return false
    }

    /** A copy of [config] with every parsed field applied (id never changes). */
    fun mergeInto(config: SubAgentConfig): SubAgentConfig = config.copy(
        name = name ?: config.name,
        role = role ?: config.role,
        description = description ?: config.description,
        systemPrompt = systemPrompt ?: config.systemPrompt,
        workspaceFolder = workspaceFolder ?: config.workspaceFolder,
        toolUids = toolUids ?: config.toolUids,
        modelProviderId = if (modelProviderId != null && model != null) modelProviderId else config.modelProviderId,
        model = if (modelProviderId != null && model != null) model else config.model,
        roundCap = roundCap ?: config.roundCap,
        tokenBudget = tokenBudget ?: config.tokenBudget,
        historyVisibility = historyVisibility ?: config.historyVisibility,
        enabled = enabled ?: config.enabled,
    )
}

object AgentConfigMd {

    /** Parse an agent config file. Never throws — an unusable file parses to an empty config. */
    fun parse(text: String): ParsedAgentConfig {
        val sections = readSections(text)
        val firstLine: (String) -> String? = { heading ->
            sections[heading]?.firstOrNull { it.isNotBlank() }?.trim()
        }
        val multiLine: (String) -> String? = { heading ->
            sections[heading]?.joinToString("\n")?.trim()?.takeIf { it.isNotBlank() }
        }
        var provider: String? = null
        var model: String? = null
        sections["model"]?.forEach { line ->
            val key = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim().takeIf { it.isNotBlank() }
            if (value == null) return@forEach
            when (key) {
                "provider" -> provider = value
                "model" -> model = value
            }
        }
        return ParsedAgentConfig(
            name = firstLine("name"),
            role = firstLine("role"),
            description = multiLine("description"),
            systemPrompt = multiLine("system prompt"),
            workspaceFolder = firstLine("workspace folder"),
            toolUids = sections["tools"]?.map { it.trim() }?.filter { it.isNotBlank() },
            modelProviderId = provider,
            model = model,
            roundCap = firstLine("max rounds")?.toIntOrNull(),
            tokenBudget = firstLine("token budget")?.toIntOrNull(),
            historyVisibility = when (firstLine("history")?.lowercase()) {
                "brief" -> HistoryVisibility.BRIEF_ONLY
                "history", "sees_history" -> HistoryVisibility.SEES_HISTORY
                else -> null
            },
            enabled = when (firstLine("enabled")?.lowercase()) {
                "on", "true", "yes" -> true
                "off", "false", "no" -> false
                else -> null
            },
        )
    }

    /** Write a full agent config as a markdown file (the write-back on save). */
    fun serialize(agent: SubAgentConfig): String = buildString {
        appendLine("> Agent config. One section per field — edit this file in any editor,")
        appendLine("> then open the agent's settings: the fields are filled from the file.")
        appendLine("> Lines starting with \">\" are notes. Unknown sections are ignored.")
        appendLine()
        appendLine("## Name")
        appendLine(agent.name)
        appendLine()
        appendLine("## Role")
        appendLine(agent.role)
        appendLine()
        appendLine("## Description")
        appendLine(agent.description)
        appendLine()
        appendLine("## System prompt")
        appendLine(agent.systemPrompt)
        appendLine()
        appendLine("## Workspace folder")
        appendLine(agent.workspaceFolder)
        appendLine()
        appendLine("## Tools")
        if (agent.toolUids.isEmpty()) appendLine("> (no tools)")
        agent.toolUids.forEach { appendLine(it) }
        appendLine()
        appendLine("## Model")
        if (agent.hasOwnModel) {
            appendLine("provider: ${agent.modelProviderId}")
            appendLine("model: ${agent.model}")
        } else {
            appendLine("> Follows Main's model (leave provider/model out).")
        }
        appendLine()
        appendLine("## Max rounds")
        appendLine(agent.roundCap.toString())
        appendLine()
        appendLine("## Token budget")
        appendLine(agent.tokenBudget.toString())
        appendLine()
        appendLine("## History")
        appendLine(if (agent.historyVisibility == HistoryVisibility.SEES_HISTORY) "history" else "brief")
        appendLine()
        appendLine("## Enabled")
        appendLine(if (agent.enabled) "on" else "off")
    }

    /** A blank starting file for a new agent (the "Create template" content). */
    fun agentTemplate(): String = serialize(
        SubAgentConfig(
            id = "agent",
            name = "My agent",
            role = "Specialist agent",
            description = "What this agent does, in one or two sentences — Main reads this when picking an agent.",
            systemPrompt = "You are My agent, a sub-agent of the Tov chat app. You work for Main (the main agent), who gave you one task in the brief below. You only know what is in the brief.\nWrite your final answer in the same language as the brief. Your final answer goes to Main only — the user never sees it.\nTo say something to the user, call the send_message tool with receiver \"user\": first, when you start, greet the user briefly (one short sentence, in the same language as the brief); then use it once at the end for a short summary (one or two sentences).",
            toolUids = listOf("web.search", "web.fetch"),
            workspaceFolder = "My agent",
        ),
    )

    /**
     * Split the file into sections: heading (lowercased, after `## `) ->
     * its lines (`>` notes dropped; blank lines kept as "" so multi-line
     * values keep their paragraph breaks; other lines trimmed of trailing
     * space only). Text before the first heading is ignored.
     */
    private fun readSections(text: String): Map<String, List<String>> {
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.trimStart().startsWith(">") -> Unit
                line.trim().startsWith("## ") -> {
                    val heading = line.trim().removePrefix("## ").trim().lowercase()
                    current = sections.getOrPut(heading) { mutableListOf() }
                }
                current == null -> Unit
                line.isBlank() -> current.add("")
                else -> current.add(line.trim())
            }
        }
        return sections
    }
}
