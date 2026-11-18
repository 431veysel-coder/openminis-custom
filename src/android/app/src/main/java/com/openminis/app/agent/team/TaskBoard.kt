package com.openminis.app.agent.team

/**
 * [MINIS-OZEL 11.5 · 37050] The task board: what the council decided to DO, and
 * whether it was actually done.
 *
 * ## Why this is a separate thing from the claim matrix
 *
 * A claim is something the council believes ("encryption is required"). A task is
 * something someone will do about it ("verify the Keystore API on Android 11").
 * The matrix dies with the run; the board outlives it, because "we decided X" is
 * worthless if the follow-up disappears when the window closes.
 *
 * ## The one rule that makes this board worth having
 *
 * **`DONE` requires evidence.** Not "the model said so" — a path that exists, a
 * URL, or a command's output. Without this, a task board is a wish list that
 * slowly fills with things everyone believes were done, which is the failure mode
 * of every agent UI that shows a checklist. [TeamTask.canBeDone] is the check and
 * [TaskBoard.moveTo] is the only way in.
 *
 * ## Why tasks are not owned by a run
 *
 * A run is over in twenty minutes. A task it opened may be worked on days later,
 * by a different team, or by the user. So [TeamTask.runId] records where it came
 * from and nothing more — it is provenance, not ownership.
 */

/** Where a task is in its life. Four states, no more. */
enum class TaskState(val wire: String) {
    /** Somebody should do this. Nobody is on it. */
    OPEN("open"),

    /** Someone is working on it right now. */
    DOING("doing"),

    /** Done, with evidence attached. The only state that requires proof. */
    DONE("done"),

    /** Cannot be done, and why. Not a failure of the board — a recorded decision. */
    DROPPED("dropped"),
    ;

    companion object {
        fun parse(raw: String?): TaskState =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: OPEN
    }
}

/**
 * What counts as proof.
 *
 * Deliberately narrow and checkable by eye: a path, a URL, or a pasted command
 * result. "I fixed it" is not evidence, and neither is a summary — a summary is a
 * claim, and this board already exists to keep claims out of the done column.
 */
enum class EvidenceKind(val wire: String) {
    /** A file or directory that exists on this device. */
    PATH("path"),

    /** A URL someone can open. */
    URL("url"),

    /** The output of a command or a test run, pasted. */
    OUTPUT("output"),
    ;

    companion object {
        fun parse(raw: String?): EvidenceKind =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: OUTPUT
    }
}

data class TaskEvidence(
    val kind: EvidenceKind,
    /** The path, URL or the output text. Required — an empty evidence row is a lie. */
    val value: String,
    /** Who attached it: a member name, or "user". */
    val by: String = "",
    val atMs: Long = System.currentTimeMillis(),
) {
    val usable: Boolean get() = value.isNotBlank()
}

/**
 * One task.
 *
 * [fromClaim] is the link back to the argument that produced it. It is not
 * required — the user can add a task by hand — but when it exists the run screen
 * can show "this is why we are doing this", which is the difference between a todo
 * list and a decision log.
 */
data class TeamTask(
    val id: String,
    val title: String,
    val detail: String = "",
    val state: TaskState = TaskState.OPEN,
    /** Member name, or "user". Empty = unassigned. */
    val owner: String = "",
    /** Provenance: the run that opened it. Empty for a task the user added. */
    val runId: String = "",
    /** The claim in that run this came from, e.g. "C3". */
    val fromClaim: String = "",
    val evidence: List<TaskEvidence> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    /** Who opened it: a member name, "chair", "user". */
    val createdBy: String = "",
) {
    /**
     * The proof rule, in one place.
     *
     * True only when the task has at least one USABLE evidence row. A row with an
     * empty value is not evidence, so it cannot be used to satisfy the rule — which
     * matters because a model that has nothing to show will happily emit
     * `{"kind":"output","value":""}`.
     */
    val canBeDone: Boolean get() = evidence.any { it.usable }

    /** Why the task cannot be marked done yet. Empty when it can. */
    fun doneRefusal(): String = if (canBeDone) {
        ""
    } else {
        "Task \"${title.take(60)}\" cannot be marked done without evidence. Attach one of: " +
            "a file path that exists, a URL, or the output of a command or test run. " +
            "\"I did it\" is not evidence — the board is what the user reads later."
    }

    /** One line for a list row. */
    fun line(): String = buildString {
        append(state.wire)
        if (owner.isNotBlank()) append(" · $owner")
        if (state == TaskState.DONE) append(" · ${evidence.size} evidence")
        if (state == TaskState.DROPPED && detail.isNotBlank()) append(" · ${detail.take(60)}")
    }
}

/** A task board change, so the caller can react (a screen, a log) without polling. */
sealed class TaskEvent {
    data class Added(val task: TeamTask) : TaskEvent()
    data class StateChanged(val task: TeamTask, val from: TaskState) : TaskEvent()
    data class EvidenceAdded(val task: TeamTask) : TaskEvent()
    data class Removed(val id: String) : TaskEvent()
}

/**
 * The board itself, as pure data plus transitions.
 *
 * Kept free of Android types so every rule below is testable on the JVM: the
 * storage layer (`TaskStore`) only serialises this, and the screens only render
 * it. That split is what lets "a done task needs evidence" be a unit test rather
 * than a thing a reviewer has to notice.
 */
data class TaskBoard(
    val tasks: List<TeamTask> = emptyList(),
) {

    fun of(state: TaskState): List<TeamTask> = tasks.filter { it.state == state }

    fun byOwner(owner: String): List<TeamTask> =
        tasks.filter { it.owner.equals(owner, ignoreCase = true) }

    /** Open or doing tasks for one member — what a member sees in its own context. */
    fun mine(owner: String): List<TeamTask> =
        tasks.filter {
            it.owner.equals(owner, ignoreCase = true) &&
                (it.state == TaskState.OPEN || it.state == TaskState.DOING)
        }

    fun find(id: String): TeamTask? = tasks.firstOrNull { it.id == id }

    /** Counters for the header line. */
    fun counts(): Map<TaskState, Int> = TaskState.entries.associateWith { state ->
        tasks.count { it.state == state }
    }

    /**
     * Add a task. A blank title is refused by returning the board unchanged: an
     * untitled task is unsearchable and unassignable, so it can only clutter.
     *
     * The ceiling is enforced here rather than in the UI because two callers add
     * tasks (a member through its tool, the chair automatically) and only one of
     * them has a screen to disable a button on.
     */
    fun add(
        task: TeamTask,
        now: Long = System.currentTimeMillis(),
    ): Triple<TaskBoard, TeamTask?, String> {
        if (task.title.isBlank()) return Triple(this, null, "A task needs a title.")
        if (tasks.size >= MAX_TASKS) {
            return Triple(
                this, null,
                "This board already has $tasks.size tasks (limit $MAX_TASKS). Finish or drop some " +
                    "before adding more — a list past this size stops being read.",
            )
        }
        val stored = task.copy(
            title = task.title.trim().take(MAX_TITLE),
            detail = task.detail.trim().take(MAX_DETAIL),
            createdAtMs = now,
            updatedAtMs = now,
        )
        return Triple(copy(tasks = tasks + stored), stored, "")
    }

    /**
     * Move a task to [state].
     *
     * Returns the BOARD UNCHANGED and a refusal string when the move is not
     * allowed, so a caller cannot accidentally apply half a transition:
     *  - `DONE` without evidence is refused ([TeamTask.doneRefusal]);
     *  - `DROPPED` without a reason is refused — a dropped task with no reason is
     *    indistinguishable from one somebody forgot, and the next run re-opens it.
     */
    fun moveTo(
        id: String,
        state: TaskState,
        reason: String = "",
        now: Long = System.currentTimeMillis(),
    ): Triple<TaskBoard, TeamTask?, String> {
        val task = find(id) ?: return Triple(this, null, "No task with id \"$id\" on this board.")
        if (task.state == state) return Triple(this, task, "")
        return when {
            state == TaskState.DONE && !task.canBeDone ->
                Triple(this, null, task.doneRefusal())
            state == TaskState.DROPPED && reason.isBlank() ->
                Triple(
                    this, null,
                    "Dropping \"${task.title.take(60)}\" needs a reason: without one the next run " +
                        "sees an abandoned task and opens it again.",
                )
            else -> {
                val updated = task.copy(
                    state = state,
                    // The reason becomes the detail for DROPPED so the board
                    // carries it; for other states detail is the task's own text.
                    detail = if (state == TaskState.DROPPED) {
                        reason.trim().take(MAX_DETAIL)
                    } else {
                        task.detail
                    },
                    updatedAtMs = now,
                )
                Triple(copy(tasks = tasks.map { if (it.id == id) updated else it }), updated, "")
            }
        }
    }

    /** Attach evidence. An unusable row is refused here rather than stored. */
    fun addEvidence(id: String, evidence: TaskEvidence): Pair<TaskBoard, String> {
        val task = find(id) ?: return this to "No task with id \"$id\" on this board."
        if (!evidence.usable) {
            return this to "Evidence needs a value: a path, a URL, or the output text."
        }
        if (task.evidence.size >= MAX_EVIDENCE) {
            return this to "This task already has ${task.evidence.size} evidence rows (limit $MAX_EVIDENCE)."
        }
        val updated = task.copy(
            evidence = task.evidence + evidence,
            updatedAtMs = evidence.atMs,
        )
        return copy(tasks = tasks.map { if (it.id == id) updated else it }) to ""
    }

    /** Assign (or reassign) a task. Empty owner clears it. */
    fun assign(id: String, owner: String, now: Long = System.currentTimeMillis()): Pair<TaskBoard, String> {
        val task = find(id) ?: return this to "No task with id \"$id\" on this board."
        val updated = task.copy(owner = owner.trim().take(60), updatedAtMs = now)
        return copy(tasks = tasks.map { if (it.id == id) updated else it }) to ""
    }

    fun remove(id: String): TaskBoard = copy(tasks = tasks.filterNot { it.id == id })

    /**
     * What a member's context should carry: its own open tasks, most urgent first.
     *
     * Ordering is deterministic (open before doing, then oldest first) so two runs
     * of the same board produce the same prompt — a prompt that reshuffles between
     * rounds makes the budget accounting and the tests meaningless.
     */
    fun promptBlock(owner: String, limit: Int = 8): String {
        val mine = mine(owner).sortedWith(compareBy({ it.state != TaskState.OPEN }, { it.createdAtMs }))
        if (mine.isEmpty()) return ""
        return buildString {
            appendLine("YOUR TASKS (from the council board):")
            mine.take(limit).forEach { t ->
                append("- [${t.id.take(8)}] ${t.state.wire}: ${t.title}")
                if (t.detail.isNotBlank()) append(" — ${t.detail.take(160)}")
                appendLine()
            }
            if (mine.size > limit) appendLine("(+${mine.size - limit} more)")
            appendLine("Mark one doing before you start it, and attach evidence before you mark it done.")
        }
    }

    companion object {
        const val MAX_TITLE = 160
        const val MAX_DETAIL = 600
        const val MAX_EVIDENCE = 8
        /** Ceiling on one board. Past this the list stops being readable. */
        const val MAX_TASKS = 200
    }
}
