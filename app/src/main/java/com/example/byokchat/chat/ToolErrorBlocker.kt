package com.example.byokchat.chat

/**
 * Blocks a tool that keeps failing in one run (24/11/2026): the lesson
 * from Bob calling get_subagent_result 8 times in a row, about 3 seconds
 * apart, with no backoff — every call erroring, and the model just
 * calling again.
 *
 * Rule: in one run, when the same tool fails [maxConsecutiveErrors]
 * times in a row, it is switched off for the rest of that run. The
 * next call of that tool does not run at all — the caller gets a
 * normal result telling it the tool is off for this run, so it can
 * use another tool or finish its turn instead of erroring again.
 *
 * Scope is one run, not one turn: a new run (Main delegating the same
 * sub again, or a new Main turn) creates a fresh blocker and starts
 * counting again. The count is per tool: a success of that tool resets
 * its count to zero, and other tools do not touch it — only repeated
 * failures of the same tool trigger the block. Pure, so it can be
 * unit-tested without Android; ChatEngine (Main's turn) and
 * SubAgentRunner (a sub's run) each own one per run.
 */
class ToolErrorBlocker(private val maxConsecutiveErrors: Int) {
    private val limit = maxConsecutiveErrors.coerceAtLeast(1)
    private val consecutiveErrors = mutableMapOf<String, Int>()
    private val disabled = mutableSetOf<String>()

    fun isDisabled(uid: String): Boolean = uid in disabled

    /**
     * The refusal for a tool that is already switched off in this run,
     * or null when the tool may still run. A normal result, not an
     * error — the caller reads it as an instruction, not a failure.
     */
    fun refusalFor(uid: String, displayName: String): String? {
        if (uid !in disabled) return null
        return "Tool '$displayName' failed $limit times in a row, so it is switched off for the rest of this run. " +
            "Do not call it again in this run. Use another tool, or finish your turn with what you have."
    }

    /** Record one outcome of a tool call in this run. */
    fun record(uid: String, isError: Boolean) {
        if (uid in disabled) return
        if (isError) {
            val count = (consecutiveErrors[uid] ?: 0) + 1
            consecutiveErrors[uid] = count
            if (count >= limit) disabled += uid
        } else {
            consecutiveErrors[uid] = 0
        }
    }
}
