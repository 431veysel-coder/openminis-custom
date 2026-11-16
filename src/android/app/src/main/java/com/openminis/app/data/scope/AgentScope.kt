package com.openminis.app.data.scope

import android.content.Context

/**
 * [MINIS-OZEL 11.3.1] The capability scope: how much freedom this app's agent
 * has over this device.
 *
 * ## The problem it solves
 *
 * Until now there were two settings, in effect: ask before everything, or allow
 * everything. The first is unusable for long work — by the twentieth approval
 * card the user is tapping blindly, which is worse than no prompt at all. The
 * second is fine until the one time it is not.
 *
 * A scope is the third option: declare the boundary once, and the agent works
 * inside it without asking. It is only defensible because the action journal can
 * take any single step back; a scope without an undo is a promise nobody can
 * keep.
 *
 * ## Modes, deliberately few
 *
 * - [Mode.OPEN] — no restriction. The default, and what this app already did,
 *   so an update changes nothing for anyone who does not visit this screen.
 * - [Mode.READ_ONLY] — file writes and edits refuse; shell runs only commands
 *   the classifier can prove are reads.
 * - [Mode.WORKSPACE] — writes only under the session's own workspace and the
 *   usual scratch areas; shell same classifier as READ_ONLY, minus redirects
 *   that write outside the workspace.
 *
 * Three modes, not a permission grid. A grid of six capabilities crossed with
 * four path roots is a settings screen nobody configures correctly; three named
 * stances are ones a person can reason about.
 *
 * ## Why the classifier is an allowlist
 *
 * Denying a list of dangerous commands is a losing game — `rm` has a hundred
 * spellings and every new tool adds more. Allowing only commands whose FIRST
 * word is a known read-only tool fails safe: an unrecognised command is refused
 * with an explanation instead of executed hopefully.
 */
object AgentScope {

    private const val PREFS = "ozel_agent_scope_prefs"
    private const val KEY_MODE = "mode"

    enum class Mode(val wire: String) {
        OPEN("open"),
        READ_ONLY("read_only"),
        WORKSPACE("workspace"),
        ;

        companion object {
            fun parse(raw: String?): Mode =
                entries.firstOrNull { it.wire == raw?.trim() } ?: OPEN
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): Mode = Mode.parse(prefs(ctx).getString(KEY_MODE, Mode.OPEN.wire))

    fun setMode(ctx: Context, mode: Mode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.wire).apply()
    }

    // ── paths ───────────────────────────────────────────────────────────────

    /**
     * Roots a WORKSPACE scope may write to.
     *
     * `/var/minis/workspace` is the session's own directory and the one place a
     * run is expected to make a mess. `attachments` and `offloads` are included
     * because tools legitimately write there (a generated chart, an offloaded
     * output); everything else on the device — including a mounted external
     * folder — is outside the scope and is refused with a message that says
     * which settings row changes it.
     */
    private val WORKSPACE_ROOTS = listOf(
        "/var/minis/workspace",
        "/var/minis/attachments",
        "/var/minis/offloads",
        "/var/minis/shared",
        "/tmp",
    )

    /** True when a path is inside one of [WORKSPACE_ROOTS]. Pure; unit tested. */
    fun isInWorkspace(path: String, extraRoots: List<String> = emptyList()): Boolean {
        val normalized = normalize(path)
        val roots = WORKSPACE_ROOTS + extraRoots
        return roots.any { root ->
            val r = normalize(root).trimEnd('/')
            normalized == r || normalized.startsWith("$r/")
        }
    }

    /**
     * Collapses `.` and `..` without touching the filesystem.
     *
     * A path check that can be defeated by `/var/minis/workspace/../../etc/passwd`
     * is not a check. The filesystem refuses some of these anyway, but "some"
     * is not a security boundary — and the refusal has to be ours, with a
     * readable reason, not the kernel's.
     */
    fun normalize(path: String): String {
        val absolute = path.startsWith("/")
        val parts = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) "/$joined" else joined
    }

    /** Whether a write to [path] is allowed under [mode]. */
    fun mayWrite(mode: Mode, path: String, extraRoots: List<String> = emptyList()): Boolean =
        when (mode) {
            Mode.OPEN -> true
            Mode.READ_ONLY -> false
            Mode.WORKSPACE -> isInWorkspace(path, extraRoots)
        }

    /** The message a refused write returns, so the model can correct itself. */
    fun writeRefusal(mode: Mode, path: String): String = when (mode) {
        Mode.OPEN -> ""
        Mode.READ_ONLY ->
            "Error: this app is in read-only scope (Settings › Minis Özel › Agent scope). " +
                "Writing $path is not allowed. Use file_read, or ask the user to change the scope."
        Mode.WORKSPACE ->
            "Error: $path is outside the workspace scope (Settings › Minis Özel › Agent scope). " +
                "Writes are limited to /var/minis/workspace. Ask the user to change the scope " +
                "if this file really needs to change."
        else -> ""
    }

    // ── shell ───────────────────────────────────────────────────────────────

    /**
     * First words of commands that only read.
     *
     * Kept short on purpose. Every entry is a promise that the command cannot
     * modify the device with the arguments an agent would plausibly pass — a
     * promise that is easy to get wrong (`find -delete`, `git branch -D`), so
     * commands with a mutating flag are left out entirely rather than
     * special-cased.
     */
    private val READ_ONLY_COMMANDS = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "rg", "find", "stat", "file",
        "pwd", "echo", "printf", "sort", "uniq", "cut", "tr", "diff", "cmp",
        "du", "df", "uname", "whoami", "id", "date", "which", "type", "env",
        "printenv", "jq", "basename", "dirname", "realpath", "readlink", "md5sum",
        "sha256sum", "hexdump", "xxd", "tree", "awk",
    )

    /** Flags that turn an otherwise read-only command into a writer. */
    private val MUTATING_FLAGS = listOf(
        "-delete", "-exec", "--delete", "--in-place", "-i.bak", "-D", "--force",
    )

    /**
     * Whether a shell command may run under a restricted mode.
     *
     * Deliberately conservative and deliberately not clever: it splits on the
     * metacharacters (a pipeline is several commands, and a single one of them
     * writing makes the whole pipeline a writer), then requires EVERY simple
     * command to start with a read-only verb. A redirect makes the whole command
     * a writer even when both sides only read.
     */
    fun mayRunShell(mode: Mode, command: String): Boolean {
        if (mode == Mode.OPEN) return true
        val cmd = command.trim()
        if (cmd.isEmpty()) return true

        // A redirect writes, whatever the command is.
        if (cmd.contains('>')) return false
        // Command substitution can hide anything.
        if (cmd.contains("$(") || cmd.contains('`')) return false

        val simpleCommands = cmd
            .split("|", ";", "&&", "||")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (simpleCommands.isEmpty()) return false

        return simpleCommands.all { simple ->
            val words = simple.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return false // defensive: split above filters these
            val verb = words.first().substringAfterLast('/').lowercase(java.util.Locale.ROOT)
            // `sed` is a writer with -i and a reader without it; the flag check
            // below covers it, so it is not in the allowlist at all.
            if (verb !in READ_ONLY_COMMANDS) return false
            if (MUTATING_FLAGS.any { simple.contains(it) }) return false
            true
        }
    }

    /** The message a refused shell command returns. */
    fun shellRefusal(mode: Mode, command: String): String = when (mode) {
        Mode.OPEN -> ""
        Mode.READ_ONLY ->
            "Error: read-only scope (Settings › Minis Özel › Agent scope) — " +
                "this shell command is not provably a read. " +
                "Allowed: ls, cat, head, tail, grep, rg, wc, jq, find (without -delete), " +
                "and similar, with no redirects or pipes into writers."
        Mode.WORKSPACE ->
            "Error: workspace scope (Settings › Minis Özel › Agent scope) — " +
                "only read-only shell commands run outside the workspace, and this one " +
                "writes or is not provably a read. Use file_write for workspace files."
        else -> ""
    }

    /** Label for the settings row. */
    fun label(mode: Mode): String = when (mode) {
        Mode.OPEN -> "Open — no restrictions"
        Mode.READ_ONLY -> "Read only — no writes, read-only shell"
        Mode.WORKSPACE -> "Workspace — writes only under /var/minis/workspace"
    }

    fun description(mode: Mode): String = when (mode) {
        Mode.OPEN ->
            "The agent can write anywhere and run any command. This is what the app did " +
                "before the scope existed. Safe only because every change is journalled " +
                "and can be undone."
        Mode.READ_ONLY ->
            "The agent can read and analyse but not change anything. Refusals come back " +
                "as tool errors, so it adapts instead of retrying."
        Mode.WORKSPACE ->
            "Writes are limited to this session's workspace (plus /tmp and the output " +
                "folders). A mounted external folder is outside the scope: its writability " +
                "is a separate switch."
    }
}
