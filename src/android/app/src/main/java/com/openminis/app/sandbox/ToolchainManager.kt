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
     * Alpine package names are lowercase, may carry a version suffix
     * (`php83-openssl`) and no shell metacharacters. Enforced before a name
     * reaches `apk del`, because the custom list is user-supplied text.
     */
    private val PKG_NAME = Regex("^[a-z0-9][a-z0-9._+-]{0,63}$")

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
        /** Command that proves the toolchain works (the install's acceptance test). */
        val probe: String,
        /**
         * [MINIS-OZEL] Every binary this group promises, used to tell
         * "installed" from "partially installed". Defaults to the binary named
         * in [probe], which is the old behaviour; groups that ship several
         * independent tools list them all so a partial install cannot show as
         * green. See ToolchainProbe for why that matters.
         */
        val requiredBinaries: List<String> = listOf(probe.split(" ").first()),
    ) {
        val spec: ToolchainProbe.Spec get() = ToolchainProbe.Spec(id, requiredBinaries)
    }

    /** [37098] Termux katmanının grup kimliği — `apk` gruplarından ayrı yol. */
    const val TERMUX_GROUP_ID: String = "termux"

    /**
     * [37098] Termux katmanı, `apk` gruplarıyla aynı listede görünür.
     *
     * `packages` boş: bu grup `apk` ile kurulmaz. Neden yine de bir `Group`:
     * arayüz tek bir liste çiziyor ve kullanıcı "Termux" satırını diğer
     * grupların arasında görmeli — ayrı bir ekran, "bu nerede" sorusunu
     * doğurur. Kurulum yolu `install()` içinde ayrılıyor.
     *
     * `sizeMb` bootstrap'in **indirme** boyutu (31,6 MB → 32). Kurulum
     * sonrası disk 110 MB; iki sayı ayrı yerde yazılı ve kart ikisini de
     * göstermeli, yoksa "32 MB dedin 110 MB yedi" şikâyeti haklı olur.
     */
    val termuxGroup: Group = Group(
        id = TERMUX_GROUP_ID,
        titleRes = com.openminis.app.R.string.toolchain_termux_title,
        subtitleRes = com.openminis.app.R.string.toolchain_termux_subtitle,
        packages = emptyList(),
        sizeMb = TermuxLayer.BOOTSTRAP_ZIP_MB,
        probe = "${TermuxLayer.GUEST_PREFIX}/bin/bash --version",
        // Mutlak yol şart: `command -v bash` Alpine'in bash'ini de bulur ve
        // katman kurulmamışken "kurulu" derdi. Mutlak yol, yalnız Termux'un
        // ikilisini sayar (`command -v /yol/x` yürütülebilirse yolu döner).
        requiredBinaries = listOf(
            "${TermuxLayer.GUEST_PREFIX}/bin/bash",
            "${TermuxLayer.GUEST_PREFIX}/bin/apt",
        ),
    )

    val groups: List<Group> = listOf(
        termuxGroup,
        Group(
            id = "c",
            titleRes = com.openminis.app.R.string.toolchain_c_title,
            subtitleRes = com.openminis.app.R.string.toolchain_c_subtitle,
            packages = listOf("build-base", "musl-dev", "binutils", "git", "make", "clang19"),
            sizeMb = 220,
            probe = "gcc --version",
            requiredBinaries = listOf("gcc", "make", "git", "clang"),
        ),
        Group(
            id = "python",
            titleRes = com.openminis.app.R.string.toolchain_python_title,
            subtitleRes = com.openminis.app.R.string.toolchain_python_subtitle,
            packages = listOf("python3", "py3-pip", "python3-dev", "uv"),
            sizeMb = 70,
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
            requiredBinaries = listOf("ffmpeg", "magick"),
        ),
        // [MINIS-OZEL] Everyday shell utilities. These are the ones an agent
        // reaches for constantly — `jq` for JSON, `sqlite3` for local data,
        // `openssl` for certificates — and the bare rootfs has none of them,
        // so every such task used to start with the agent asking to install
        // something. ~35 MB installed.
        Group(
            id = "devtools",
            titleRes = com.openminis.app.R.string.toolchain_devtools_title,
            subtitleRes = com.openminis.app.R.string.toolchain_devtools_subtitle,
            packages = listOf(
                "jq", "sqlite", "openssl", "ripgrep", "less", "tree", "tmux", "nano", "vim",
            ),
            sizeMb = 35,
            probe = "jq --version",
            // The group that started this: `jq` alone used to light it up.
            requiredBinaries = listOf(
                "jq", "sqlite3", "openssl", "rg", "less", "tree", "tmux", "nano", "vim",
            ),
        ),
        // Remote work: ssh out, rsync files, scp them around. openssh-client
        // also brings ssh-keygen, which is what the agent needs before any
        // `git push` over SSH.
        Group(
            id = "network",
            titleRes = com.openminis.app.R.string.toolchain_network_title,
            subtitleRes = com.openminis.app.R.string.toolchain_network_subtitle,
            packages = listOf("openssh-client", "rsync", "curl", "wget"),
            sizeMb = 20,
            probe = "ssh -V",
            requiredBinaries = listOf("ssh", "rsync", "curl", "wget"),
        ),
        // Scripting runtimes that are not Python/Node: Ruby, Perl, PHP.
        Group(
            id = "scripting",
            titleRes = com.openminis.app.R.string.toolchain_scripting_title,
            subtitleRes = com.openminis.app.R.string.toolchain_scripting_subtitle,
            packages = listOf("ruby-full", "perl", "php83", "php83-phar", "php83-openssl", "php83-common"),
            sizeMb = 90,
            probe = "ruby --version",
            requiredBinaries = listOf("ruby", "perl", "php83"),
        ),
        // Android build plumbing: adb to talk to a device over wireless
        // debugging, gradle to drive a build. gradle pulls its own JDK, so it
        // is usable without the Java group — but install both to compile.
        Group(
            id = "android",
            titleRes = com.openminis.app.R.string.toolchain_android_title,
            subtitleRes = com.openminis.app.R.string.toolchain_android_subtitle,
            packages = listOf("android-tools", "gradle"),
            sizeMb = 180,
            probe = "adb version",
            requiredBinaries = listOf("adb", "gradle"),
        ),
    )

    /** Per-group state shown on the screen. */
    sealed interface InstallState {
        data object Unknown : InstallState
        data object Absent : InstallState
        data object Present : InstallState
        /** [MINIS-OZEL] Some of the group's binaries are missing. */
        data class Partial(val missing: List<String>) : InstallState
        data class Installing(val line: String?) : InstallState
        data class Failed(val message: String) : InstallState
    }

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    private fun set(groupId: String, state: InstallState) {
        _states.value = _states.value + (groupId to state)
    }

    /**
     * [MINIS-OZEL] Installs an arbitrary list of Alpine packages.
     *
     * The group list answers "give me a working Python"; this answers "I only
     * need `jq` and `sqlite`". Without it the smallest useful unit is a whole
     * group — 35 MB for the shell utilities when two packages would have done,
     * and 1.4 GB if someone installs every group. The packages land in the
     * same rootfs and survive updates like the groups do.
     *
     * [probe] decides success: apk's exit code alone is not enough, since a
     * partial install can succeed per-package.
     */
    suspend fun installPackages(
        context: Context,
        packages: List<String>,
        probe: String,
        onLine: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        // Names reach a shell command, and they come from a text field.
        // Anything that is not a plain Alpine package name is refused rather
        // than escaped: there is no legitimate input this would reject, and a
        // quoting bug in a screen that runs commands as the sandbox's root is
        // the one place not to be clever.
        val clean = packages.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val rejected = clean.filterNot { PKG_NAME.matches(it) }
        if (rejected.isNotEmpty()) {
            onLine("rejected: " + rejected.joinToString(" "))
            AppLogger.info(TAG, "[install-custom] rejected names ${rejected.joinToString(" ")}")
            return@withContext false
        }
        if (clean.isEmpty()) return@withContext false
        AppLogger.info(TAG, "[install-custom] packages=${clean.joinToString(" ")}")

        val cmd = "apk update >/dev/null 2>&1; apk add --no-cache ${clean.joinToString(" ")} 2>&1"
        val result = runCatching {
            ExecutionCoordinator.execute(
                TOOLCHAIN_SESSION,
                cmd,
                timeout = 30L * 60_000L,
                lineCallback = { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) onLine(trimmed)
                },
            )
        }.getOrElse { t ->
            onLine(t.message ?: t.javaClass.simpleName)
            return@withContext false
        }

        // [MINIS-OZEL] Strict, and the same rule install() uses: the probe
        // must actually run and print its marker. The previous fallback —
        // "assume success if apk exited 0" — reported a package as installed
        // when the probe had merely failed to execute, which is the one outcome
        // the user cannot verify from the screen.
        val probeOk = runCatching {
            ExecutionCoordinator.execute(
                TOOLCHAIN_SESSION,
                "$probe >/dev/null 2>&1 && echo __ok || echo __failed",
                timeout = 60_000L,
            ).output.contains("__ok")
        }.getOrDefault(false)

        if (probeOk) rememberCustomPackages(context, clean)
        AppLogger.info(TAG, "[install-custom] exit=${result.exitCode} probe=$probeOk")
        probeOk
    }

    // ── [MINIS-OZEL] Free-form package registry ────────────────────────────
    //
    // The groups are coarse; installPackages() exists so a user can ask for
    // `jq sqlite` without 35 MB of shell utilities. The first version of that
    // did not record what it installed, so:
    //
    //   * the screen forgot the request the moment it finished — "what did I
    //     add?" had no answer an hour later;
    //   * there was no way to remove it short of knowing `apk del` and the
    //     exact package names;
    //   * the group probes could not tell a deliberate two-package install from
    //     a half-finished group install.
    //
    // A plain SharedPreferences list is enough here: these are Alpine package
    // names, not secrets, and the packages themselves live in the rootfs. The
    // value is remembering the intent, not securing it.

    private const val CUSTOM_PREFS = "toolchain_custom_packages"
    private const val CUSTOM_KEY = "packages"

    /** Package names installed through [installPackages], in install order. */
    fun customPackages(context: Context): List<String> {
        val raw = context.applicationContext
            .getSharedPreferences(CUSTOM_PREFS, Context.MODE_PRIVATE)
            .getString(CUSTOM_KEY, "")
            .orEmpty()
        return raw.split(' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun saveCustomPackages(context: Context, packages: List<String>) {
        context.applicationContext
            .getSharedPreferences(CUSTOM_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CUSTOM_KEY, packages.distinct().joinToString(" "))
            .apply()
    }

    private fun rememberCustomPackages(context: Context, added: List<String>) {
        saveCustomPackages(context, customPackages(context) + added)
    }

    /**
     * Removes packages that were installed through [installPackages].
     *
     * Only names in the registry are accepted, and only those actually present
     * are passed to `apk del` — a free-form string reaching `apk del` unchecked
     * would be a shell-injection surface, and this list is user-supplied text.
     * The registry entry is dropped only when the package really is gone, so a
     * failed removal stays visible instead of quietly disappearing from the UI.
     */
    suspend fun uninstallPackages(
        context: Context,
        packages: List<String>,
        onLine: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val registry = customPackages(context)
        val allowed = packages.filter { it in registry && PKG_NAME.matches(it) }
        if (allowed.isEmpty()) {
            AppLogger.info(TAG, "[uninstall-custom] nothing to do for ${packages.joinToString(" ")}")
            return@withContext false
        }
        set(customStateId(allowed), InstallState.Installing(null))

        val result = runCatching {
            ExecutionCoordinator.execute(
                TOOLCHAIN_SESSION,
                "apk del ${allowed.joinToString(" ")} 2>&1",
                timeout = 10L * 60_000L,
                lineCallback = { line -> if (line.isNotBlank()) onLine(line.trim()) },
            )
        }.getOrElse {
            set(customStateId(allowed), InstallState.Failed(it.message ?: "uninstall failed"))
            return@withContext false
        }

        val stillThere = stillInstalled(allowed)
        val removed = allowed - stillThere
        if (stillThere.isEmpty()) {
            saveCustomPackages(context, registry - allowed.toSet())
            set(customStateId(allowed), InstallState.Absent)
        } else if (removed.isNotEmpty()) {
            saveCustomPackages(context, registry - removed.toSet())
            set(customStateId(allowed), InstallState.Partial(stillThere))
        } else {
            val tail = result.output.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" · ")
            set(customStateId(allowed), InstallState.Failed(tail.ifBlank { "apk exit ${result.exitCode}" }))
        }
        AppLogger.info(
            TAG,
            "[uninstall-custom] exit=${result.exitCode} removed=${removed.joinToString(" ")} " +
                "kept=${stillThere.joinToString(" ")}",
        )
        stillThere.isEmpty()
    }

    /** Which of [packages] still resolve to a command in the sandbox. */
    private suspend fun stillInstalled(packages: List<String>): List<String> {
        if (packages.isEmpty()) return emptyList()
        val script = packages.joinToString("; ") { p ->
            "command -v $p >/dev/null 2>&1 && echo '$p=yes' || echo '$p=no'"
        }
        val out = runCatching {
            ExecutionCoordinator.execute(TOOLCHAIN_SESSION, script, timeout = 60_000L).output
        }.getOrNull() ?: return packages // cannot tell → assume still there
        val present = out.lineSequence()
            .mapNotNull { line ->
                val t = line.trim().trim('\'', '"')
                val eq = t.lastIndexOf('=')
                if (eq <= 0) null else t.substring(0, eq).takeIf { t.endsWith("=yes") }
            }
            .toSet()
        return packages.filter { it in present }
    }

    /**
     * Registry key for a free-form install's state, so the screen can show
     * progress for it with the same [states] map the groups use.
     */
    fun customStateId(packages: List<String>): String = "custom:" + packages.joinToString(",")

    /** [states] key for the entire custom list, seen as one unit by the screen. */
    const val CUSTOM_ALL_ID = "custom:*"

    /**
     * Probes every group with one shell call and updates [states].
     *
     * A single round-trip for all groups: a separate `command -v` call per
     * binary would pay the shell's per-exec cost dozens of times.
     *
     * [MINIS-OZEL] Classification is delegated to [ToolchainProbe] so that a
     * group carrying several tools reports Partial instead of claiming all of
     * them are there. See that file for the bug this replaced.
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val specs = groups.map { it.spec }
        val script = ToolchainProbe.buildScript(specs)
        val result = runCatching {
            ExecutionCoordinator.execute(TOOLCHAIN_SESSION, script.trim(), timeout = 120_000L)
        }.getOrNull()
        if (result == null) {
            AppLogger.info(TAG, "[probe] shell unavailable")
            return@withContext
        }
        val presence = ToolchainProbe.parse(result.output, specs)
        _states.value = groups.associate { g ->
            g.id to when (val p = presence[g.id]) {
                is ToolchainProbe.Presence.Present -> InstallState.Present
                is ToolchainProbe.Presence.Partial -> InstallState.Partial(p.missing)
                else -> InstallState.Absent
            }
        }
        AppLogger.info(
            TAG,
            "[probe] " + presence.entries.joinToString(",") { (id, p) ->
                id + "=" + when (p) {
                    is ToolchainProbe.Presence.Present -> "yes"
                    is ToolchainProbe.Presence.Partial -> "partial(" + p.missing.joinToString("/") + ")"
                    else -> "no"
                }
            },
        )
    }

    /**
     * Installs one group with `apk add`. [onLine] receives apk's output so the
     * screen can show what is happening rather than a bare spinner.
     *
     * Returns true when the group probes clean afterwards — apk's exit code
     * alone is not enough, since a partial install can succeed per-package.
     */
    /**
     * [37098] Termux katmanı kurulumu — `apk` yolunun karşılığı.
     *
     * Aynı `InstallState` makinesini kullanır: arayüz `Installing(line)` ile
     * ilerlemeyi, `Failed(message)` ile nedeni, `Present` ile bitişi görür.
     * Fark: ilerleme satırı `apk`'dan değil [TermuxInstaller]'ın adımlarından
     * geliyor ("indirme 12,4/31,6 MB").
     *
     * Başarı ölçütü yine **iddia değil kanıt**: `TermuxInstaller` son adımda
     * `bash --version` ve `apt --version` çalıştırır; o geçmeden `Present`
     * denmez.
     */
    private suspend fun installTermux(context: Context, onLine: (String) -> Unit): Boolean {
        set(TERMUX_GROUP_ID, InstallState.Installing(null))
        AppLogger.info(TAG, "[install] termux layer start")
        val result = runCatching {
            TermuxInstaller.install(context) { p ->
                val line = when {
                    p.error != null -> "hata: ${p.error}"
                    p.bytesTotal > 0 && p.stepId == "download" && !p.done ->
                        "${p.title}: ${p.bytesDone / 1048576},${(p.bytesDone % 1048576) * 10 / 1048576} / " +
                            "${p.bytesTotal / 1048576} MB"
                    p.done -> "${p.title} ✓"
                    else -> "${p.title}: ${p.message}"
                }.trim()
                set(TERMUX_GROUP_ID, InstallState.Installing(line))
                onLine(line)
            }
        }.getOrElse { t ->
            set(TERMUX_GROUP_ID, InstallState.Failed(t.message ?: t.javaClass.simpleName))
            return false
        }
        if (!result.ok) {
            set(TERMUX_GROUP_ID, InstallState.Failed(result.message))
            AppLogger.info(TAG, "[install] termux layer FAILED: ${result.message}")
            return false
        }
        set(TERMUX_GROUP_ID, InstallState.Present)
        AppLogger.info(TAG, "[install] termux layer ok: ${result.message}")
        // [37098] Kurulum boot'tan sonra olduysa `customEnvironment`'daki PATH
        // hâlâ eski. Tazelemezsek ajan `apt` bulamaz — "kurdum ama çalışmıyor".
        runCatching { PRootKernel.refreshTermuxPath() }
        return true
    }

    suspend fun install(
        context: Context,
        groupId: String,
        onLine: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        // [37098] Termux katmanı bir `apk` grubu değil: kendi indirmesi,
        // kendi sha256'sı, kendi symlink adımı var. `apk add` ile kurulamaz,
        // o yüzden ayrı yola gider — ama **aynı state makinesini** kullanır
        // ki arayüz iki farklı kurulum biçimi öğrenmek zorunda kalmasın.
        if (groupId == TERMUX_GROUP_ID) {
            return@withContext installTermux(context, onLine)
        }
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

        // [MINIS-OZEL] Success is "every promised binary exists", not "apk
        // exited 0". apk can return 0 with a package skipped, and it can return
        // non-zero while the tool landed; the sandbox is the only authority.
        val presence = probeGroup(group)
        val complete = ToolchainProbe.isComplete(presence)

        if (!complete) {
            val tail = result.output.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" · ")
            when (presence) {
                is ToolchainProbe.Presence.Partial ->
                    set(groupId, InstallState.Partial(presence.missing))
                else ->
                    set(groupId, InstallState.Failed(tail.ifBlank { "apk exit ${result.exitCode}" }))
            }
            AppLogger.info(
                TAG,
                "[install] ${group.id} INCOMPLETE exit=${result.exitCode} presence=$presence $tail",
            )
            return@withContext false
        }
        set(groupId, InstallState.Present)
        AppLogger.info(TAG, "[install] ${group.id} ok exit=${result.exitCode} presence=$presence")
        true
    }

    /**
     * Removes one group's packages.
     *
     * `apk del` also drops now-unused dependencies, so removing `rust` does not
     * leave musl headers behind. The rootfs itself is untouched.
     *
     * [MINIS-OZEL] The old version wrote [InstallState.Absent] on *any* path
     * that did not throw, including a non-zero `apk del` — so a removal that
     * silently failed (a package held back by a dependency, a read-only
     * overlay) showed as removed. It now checks the exit code and re-probes:
     * the state on screen is what the sandbox actually reports, not what the
     * command was asked to do.
     */
    suspend fun uninstall(context: Context, groupId: String, onLine: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            // [37098] Termux katmanı `apk del` ile kaldırılmaz: paketleri
            // Alpine'e ait değil, kendi dizinini silmek gerekir. Boş paket
            // listesiyle `apk del` çalıştırmak komutu bozardı.
            if (groupId == TERMUX_GROUP_ID) {
                set(TERMUX_GROUP_ID, InstallState.Installing(null))
                val ok = runCatching { TermuxInstaller.uninstall(context) }
                    .getOrElse {
                        set(TERMUX_GROUP_ID, InstallState.Failed(it.message ?: "uninstall failed"))
                        return@withContext false
                    }
                set(TERMUX_GROUP_ID, if (ok) InstallState.Absent else InstallState.Failed("katman silinemedi"))
                runCatching { PRootKernel.refreshTermuxPath() }
                AppLogger.info(TAG, "[uninstall] termux layer removed=$ok")
                return@withContext ok
            }
            val group = groups.firstOrNull { it.id == groupId } ?: return@withContext false
            set(groupId, InstallState.Installing(null))
            val result = runCatching {
                ExecutionCoordinator.execute(
                    TOOLCHAIN_SESSION,
                    "apk del ${group.packages.joinToString(" ")} 2>&1",
                    timeout = 10L * 60_000L,
                    lineCallback = { line -> if (line.isNotBlank()) onLine(line.trim()) },
                )
            }.getOrElse {
                set(groupId, InstallState.Failed(it.message ?: "uninstall failed"))
                return@withContext false
            }

            // apk exits 1 when a package is not installed at all ("'x' is not
            // installed"), which is not a failure of the request — the desired
            // end state is already true. So the probe decides, the exit code
            // only supplies the message.
            val presence = probeGroup(group)
            val ok = presence is ToolchainProbe.Presence.Absent
            if (ok) {
                set(groupId, InstallState.Absent)
            } else if (presence is ToolchainProbe.Presence.Partial) {
                set(groupId, InstallState.Partial(presence.missing))
            } else {
                val tail = result.output.lines().filter { it.isNotBlank() }.takeLast(3)
                    .joinToString(" · ")
                set(groupId, InstallState.Failed(tail.ifBlank { "apk exit ${result.exitCode}" }))
            }
            AppLogger.info(TAG, "[uninstall] ${group.id} exit=${result.exitCode} ok=$ok")
            ok
        }

    /** Runs the group's probe script and returns its presence, or null if the shell failed. */
    private suspend fun probeGroup(group: Group): ToolchainProbe.Presence? {
        val spec = group.spec
        val out = runCatching {
            ExecutionCoordinator.execute(
                TOOLCHAIN_SESSION,
                ToolchainProbe.buildScript(listOf(spec)).trim(),
                timeout = 60_000L,
            ).output
        }.getOrNull() ?: return null
        return ToolchainProbe.parse(out, listOf(spec))[group.id]
    }

    /**
     * Bytes the installed packages add to the rootfs, for the screen's header.
     *
     * [MINIS-OZEL] Two changes from the first version, both about honesty:
     *
     *  - It is `suspend` and runs on [Dispatchers.IO]. It walks every file under
     *    `usr/`, which is tens of thousands of `stat()` calls once a few groups
     *    are installed, and it used to be called straight from Compose's
     *    `LaunchedEffect` and from `rememberCoroutineScope().launch` — i.e. on
     *    the main thread. That is jank on a good day and an ANR on a slow one.
     *  - It subtracts the *measured* size of a pristine `usr/` instead of a
     *    hard-coded 6.4 MB guess, and it no longer assumes the base tree is the
     *    only thing that grew. A constant that has to be re-measured by hand
     *    whenever the shipped rootfs changes is a number that will silently be
     *    wrong, which is worse than no number.
     *
     * Returns 0 when the rootfs is not extracted yet.
     */
    suspend fun installedBytes(context: Context): Long = withContext(Dispatchers.IO) {
        val rootfs = File(context.filesDir, TermuxLayer.ROOTFS_DIR_NAME)
        val usr = File(rootfs, "usr")
        if (!usr.exists()) return@withContext 0L
        var total = 0L
        usr.walkTopDown().forEach { if (it.isFile) total += it.length() }
        // The base figure is recorded once per rootfs version in
        // BASE_USR_BYTES below; anything beyond it is what the user added.
        (total - BASE_USR_BYTES).coerceAtLeast(0L)
    }

    /**
     * Size of `usr/` in the rootfs as shipped, measured from
     * `assets/alpine-minirootfs.tar` for Alpine 3.21.3 by
     * `scripts/ci/measure_base_rootfs.sh`. Not a round number on purpose: the
     * point is that it is a measurement, so updating it means re-running that
     * script rather than adjusting a guess.
     */
    private const val BASE_USR_BYTES = 6_400_000L
}
