package com.openminis.app.sandbox

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [MINIS-OZEL] Installs language/build toolchains into the sandbox, once.
 *
 * ## Why this exists
 *
 * The APK ships a deliberately bare Alpine rootfs — 8 MB of busybox, `apk`,
 * `curl` and the certificates. Nothing else. That is the right trade (a
 * bundled gcc+go+rust+node would be ~700 MB and would land on every app
 * update), but it left the user with a shell that could not build anything
 * until they knew to type `apk add` themselves.
 *
 * This turns that one-time setup into a screen: pick a toolchain, it runs the
 * same `apk add` the agent would, and the result lands in the extracted rootfs
 * at `filesDir/alpine-rootfs`. That directory survives app updates — it is
 * only removed on uninstall — so a toolchain is downloaded **once** and then
 * lives in the app like a built-in.
 *
 * ## Why `apk` rather than our own tarballs
 *
 * It is what the original does: Alpine's own package manager, Alpine's own
 * repositories, real system packages installed in the real locations. Nothing
 * to keep in sync, nothing to re-cut when Alpine moves. The trade is that a
 * first install needs a network; thereafter nothing does.
 */
object ToolchainManager {

    private const val TAG = "Toolchain"

    /** The session id used for these installs; they belong to no chat. */
    private const val TOOLCHAIN_SESSION = "minis-toolchain-setup"

    /**
     * One installable group. [packages] are Alpine package names; [sizeMb] is
     * the download+installed estimate shown before the user commits, taken
     * from `apk info -s` in a live sandbox.
     */
    data class Group(
        val id: String,
        val titleRes: Int,
        val subtitleRes: Int,
        val packages: List<String>,
        val sizeMb: Int,
        /** Command that proves the toolchain is present. */
        val probe: String,
    )

    val groups: List<Group> = listOf(
        Group(
            id = "c",
            titleRes = com.openminis.app.R.string.toolchain_c_title,
            subtitleRes = com.openminis.app.R.string.toolchain_c_subtitle,
            packages = listOf("build-base", "musl-dev", "binutils", "git", "make"),
            sizeMb = 180,
            probe = "gcc --version",
        ),
        Group(
            id = "python",
            titleRes = com.openminis.app.R.string.toolchain_python_title,
            subtitleRes = com.openminis.app.R.string.toolchain_python_subtitle,
            packages = listOf("python3", "py3-pip", "python3-dev"),
            sizeMb = 45,
            probe = "python3 --version",
        ),
        Group(
            id = "node",
            titleRes = com.openminis.app.R.string.toolchain_node_title,
            subtitleRes = com.openminis.app.R.string.toolchain_node_subtitle,
            packages = listOf("nodejs", "npm"),
            sizeMb = 75,
            probe = "node --version",
        ),
        Group(
            id = "go",
            titleRes = com.openminis.app.R.string.toolchain_go_title,
            subtitleRes = com.openminis.app.R.string.toolchain_go_subtitle,
            packages = listOf("go"),
            sizeMb = 190,
            probe = "go version",
        ),
        Group(
            id = "rust",
            titleRes = com.openminis.app.R.string.toolchain_rust_title,
            subtitleRes = com.openminis.app.R.string.toolchain_rust_subtitle,
            packages = listOf("rust", "cargo"),
            sizeMb = 200,
            probe = "rustc --version",
        ),
        Group(
            id = "java",
            titleRes = com.openminis.app.R.string.toolchain_java_title,
            subtitleRes = com.openminis.app.R.string.toolchain_java_subtitle,
            packages = listOf("openjdk17-jdk"),
            sizeMb = 260,
            probe = "javac -version",
        ),
        Group(
            id = "media",
            titleRes = com.openminis.app.R.string.toolchain_media_title,
            subtitleRes = com.openminis.app.R.string.toolchain_media_subtitle,
            packages = listOf("ffmpeg", "imagemagick"),
            sizeMb = 120,
            probe = "ffmpeg -version",
        ),
    )

    /** Per-group state shown on the screen. */
    sealed interface InstallState {
        data object Unknown : InstallState
        data object Absent : InstallState
        data object Present : InstallState
        data class Installing(val line: String?) : InstallState
        data class Failed(val message: String) : InstallState
    }

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    private fun set(groupId: String, state: InstallState) {
        _states.value = _states.value + (groupId to state)
    }

    /**
     * Probes every group with one shell call and updates [states].
     *
     * A single round-trip for all groups: seven separate `command -v` calls
     * would pay the shell's per-exec cost seven times.
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val script = buildString {
            groups.forEach { g ->
                append("if ${g.probe.split(" ").first()} --version >/dev/null 2>&1 ")
                append("|| command -v ${g.probe.split(" ").first()} >/dev/null 2>&1; ")
                append("then echo '${g.id}:yes'; else echo '${g.id}:no'; fi; ")
            }
        }
        val result = runCatching {
            ExecutionCoordinator.execute(TOOLCHAIN_SESSION, script.trim(), timeout = 120_000L)
        }.getOrNull()
        if (result == null) {
            AppLogger.info(TAG, "[probe] shell unavailable")
            return@withContext
        }
        val found = result.output.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(':')
                if (parts.size == 2 && parts[1].trim().equals("yes", true)) parts[0] else null
            }
            .toSet()
        _states.value = groups.associate { g ->
            g.id to if (g.id in found) InstallState.Present else InstallState.Absent
        }
        AppLogger.info(TAG, "[probe] present=${found.joinToString(",")}")
    }

    /**
     * Installs one group with `apk add`. [onLine] receives apk's output so the
     * screen can show what is happening rather than a bare spinner.
     *
     * Returns true when the group probes clean afterwards — apk's exit code
     * alone is not enough, since a partial install can succeed per-package.
     */
    suspend fun install(
        context: Context,
        groupId: String,
        onLine: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val group = groups.firstOrNull { it.id == groupId } ?: return@withContext false
        set(groupId, InstallState.Installing(null))
        AppLogger.info(TAG, "[install] ${group.id} packages=${group.packages.joinToString(" ")}")

        val cmd = "apk update >/dev/null 2>&1; apk add --no-cache ${group.packages.joinToString(" ")} 2>&1"
        val result = runCatching {
            ExecutionCoordinator.execute(
                TOOLCHAIN_SESSION,
                cmd,
                timeout = 30L * 60_000L,
                lineCallback = { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        set(groupId, InstallState.Installing(trimmed))
                        onLine(trimmed)
                    }
                },
            )
        }.getOrElse { t ->
            set(groupId, InstallState.Failed(t.message ?: t.javaClass.simpleName))
            return@withContext false
        }

        val probeOk = runCatching {
            ExecutionCoordinator.execute(
                TOOLCHAIN_SESSION,
                "${group.probe} >/dev/null 2>&1 && echo __ok || echo __failed",
                timeout = 60_000L,
            ).output.contains("__ok")
        }.getOrDefault(false)

        if (result.exitCode != 0 && !probeOk) {
            val tail = result.output.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" · ")
            set(groupId, InstallState.Failed(tail.ifBlank { "apk exit ${result.exitCode}" }))
            AppLogger.info(TAG, "[install] ${group.id} FAILED exit=${result.exitCode} $tail")
            return@withContext false
        }
        set(groupId, if (probeOk) InstallState.Present else InstallState.Absent)
        AppLogger.info(TAG, "[install] ${group.id} ok exit=${result.exitCode} probe=$probeOk")
        probeOk
    }

    /**
     * Removes one group's packages.
     *
     * `apk del` also drops now-unused dependencies, so removing `rust` does not
     * leave musl headers behind. The rootfs itself is untouched.
     */
    suspend fun uninstall(context: Context, groupId: String, onLine: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val group = groups.firstOrNull { it.id == groupId } ?: return@withContext false
            set(groupId, InstallState.Installing(null))
            runCatching {
                ExecutionCoordinator.execute(
                    TOOLCHAIN_SESSION,
                    "apk del ${group.packages.joinToString(" ")} 2>&1",
                    timeout = 10L * 60_000L,
                    lineCallback = { line -> if (line.isNotBlank()) onLine(line.trim()) },
                )
            }.onFailure {
                set(groupId, InstallState.Failed(it.message ?: "uninstall failed"))
                return@withContext false
            }
            set(groupId, InstallState.Absent)
            AppLogger.info(TAG, "[uninstall] ${group.id}")
            true
        }

    /** Bytes the installed packages add to the rootfs, for the screen's header. */
    fun installedBytes(context: Context): Long {
        val rootfs = File(context.filesDir, "alpine-rootfs")
        // /usr is where apk puts everything a toolchain brings; the base rootfs
        // is ~6 MB there, so subtracting it gives a usable "what did I add".
        val usr = File(rootfs, "usr")
        if (!usr.exists()) return 0L
        var total = 0L
        usr.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return (total - BASE_USR_BYTES).coerceAtLeast(0L)
    }

    /** `usr/` size of the shipped rootfs; measured from assets/alpine-minirootfs.tar. */
    private const val BASE_USR_BYTES = 6_400_000L
}
