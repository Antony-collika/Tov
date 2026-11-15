package com.example.byokchat.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The sub-agent team, loaded from a user-editable config file (Phase 3).
 *
 * File choice — recorded, because it was a real decision: agents.json lives
 * in the app's private files dir (next to request-log), NOT next to
 * tools.md in the playground. tools.md has no fixed home — the Tools card
 * lets the user point it at any file anywhere (or type the text in) — so
 * there is no stable "alongside tools.md" to use, and the team must load
 * at startup, before (and even without) any playground folder being
 * chosen. Like ToolPrefs and the request layout, the team is app-level
 * config; the file is still the source of truth and the Agent settings
 * screen edits the file (it never keeps a separate copy). If the user
 * later wants a copy in Storage, that is an export on top of this file,
 * not a second source of truth.
 *
 * Behaviour:
 * - First launch (no file): the seed team (Research only) is written out,
 *   so the user can find and edit the file.
 * - Corrupt file: the team falls back to the seed, the error is recorded
 *   in [lastLoadError], and the file is left untouched so nothing is lost.
 * - Every load/save updates [snapshot], so code without access to the
 *   store (history export/import, chat headers) can still resolve an
 *   agent id to its display name.
 */
class SubAgentStore(private val file: File) {
    companion object {
        const val FILE_NAME = "agents.json"

        /** Last loaded team, for call sites without the store. Updated on every load/save. */
        @Volatile
        var snapshot: SubAgentTeam = defaultTeam()
            private set
    }

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    /** Last load problem (null when the file loaded fine). */
    private val _lastLoadError = MutableStateFlow<String?>(null)
    val lastLoadError: StateFlow<String?> = _lastLoadError.asStateFlow()

    private val _team = MutableStateFlow(loadFromDisk())
    val team: StateFlow<SubAgentTeam> = _team.asStateFlow()

    private fun loadFromDisk(): SubAgentTeam {
        if (!file.exists()) {
            val seed = defaultTeam()
            runCatching { writeFile(seed) }
            snapshot = seed
            return seed
        }
        return try {
            val parsed = json.decodeFromString<SubAgentTeam>(file.readText()).sanitized()
            snapshot = parsed
            parsed
        } catch (e: Exception) {
            _lastLoadError.value =
                "Could not read agents.json (${e.message ?: e.javaClass.simpleName}); " +
                    "using the default team. The file was left untouched."
            val seed = defaultTeam()
            snapshot = seed
            seed
        }
    }

    private fun writeFile(team: SubAgentTeam) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(SubAgentTeam.serializer(), team))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /** Save a whole team (sanitized first). Returns failure with the reason instead of throwing. */
    fun save(team: SubAgentTeam): Result<Unit> = runCatching {
        val clean = team.sanitized()
        writeFile(clean)
        _team.value = clean
        snapshot = clean
    }

    /** Replace one agent (matched by id). Fails when the id is unknown or the agent is invalid. */
    fun updateAgent(agent: SubAgentConfig): Result<Unit> {
        val errors = agent.validate()
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString(" ")))
        val current = _team.value
        if (current.byId(agent.id) == null) {
            return Result.failure(IllegalArgumentException("Unknown agent '${agent.id}'."))
        }
        return save(current.copy(agents = current.agents.map { if (it.id == agent.id) agent else it }))
    }

    /** Add a new agent. Fails when the id is already taken or the agent is invalid. */
    fun addAgent(agent: SubAgentConfig): Result<Unit> {
        val errors = agent.validate()
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString(" ")))
        val current = _team.value
        if (current.byId(agent.id) != null) {
            return Result.failure(IllegalArgumentException("An agent with id '${agent.id}' already exists."))
        }
        return save(current.copy(agents = current.agents + agent))
    }

    /** Remove an agent by id. Fails when the id is unknown. */
    fun removeAgent(id: String): Result<Unit> {
        val current = _team.value
        if (current.byId(id) == null) {
            return Result.failure(IllegalArgumentException("Unknown agent '$id'."))
        }
        return save(current.copy(agents = current.agents.filterNot { it.id == id }))
    }
}
