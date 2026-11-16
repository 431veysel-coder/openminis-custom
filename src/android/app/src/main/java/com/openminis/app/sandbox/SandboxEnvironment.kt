package com.openminis.app.sandbox

import java.io.File

/**
 * [MINIS-OZEL] The Linux environments this app can host, and what each one
 * costs and speaks.
 *
 * ## Why more than one, in one app
 *
 * The bundled Alpine rootfs is 3.9 MB compressed and boots in a couple of
 * seconds. For an agent that runs hundreds of small commands a minute, that is
 * not a compromise — it is the right tool. But it is **musl**, and a great many
 * prebuilt binaries on the internet are **glibc**: a Node release tarball, a
 * Go toolchain from upstream, a vendor's CLI, anything shipped as an AppImage
 * or a static-ish distro build. Those fail on Alpine with `not found` — the
 * error that looks like a missing file and is actually "this ELF interpreter
 * does not exist here".
 *
 * The alternative people reach for is "just ship Debian instead". That trades a
 * 4 MB environment for a 95 MB one and a five-second boot for a two-minute one,
 * for every user, to serve the minority of tasks that need glibc.
 *
 * So: **both, in one app, with the small one as the default.** Alpine answers
 * almost everything; when something needs glibc it can be installed once and
 * used for that command. That is only possible inside one app — two separate
 * APKs (`com.minis.debian` alongside this one) could not do it, because the
 * agent's shell lives in one process and one filesystem. A second app would be
 * a second sandbox the agent cannot reach, which is exactly the thing that is
 * not wanted here.
 *
 * ## Why the Debian image is downloaded and Alpine is not
 *
 * Alpine is 3.9 MB: bundling it costs every user one download they will use.
 * Debian is 95 MB compressed, 350 MB extracted, and maybe a third of users will
 * want it: bundling it would add 60 MB to an APK that people update over mobile
 * data, for a feature most of them never turn on. Bundled versus on-demand is
 * decided per environment by [bundled].
 *
 * ## Archive formats
 *
 * Alpine publishes `.tar.gz`; the LXC Debian images publish `.tar.xz`. Both are
 * decompressed in [RootfsManager] before extraction, which needs an XZ decoder
 * (a `.tar.xz` handed to a gzip reader fails on the first block and looks like
 * a corrupt download).
 */
enum class SandboxEnvironment(
    /** Stable id: persisted in prefs, used in directory names. Never rename. */
    val id: String,
    /** Shown in Settings. */
    val displayName: String,
    /** Sub-directory under `filesDir/rootfs/`. */
    val dirName: String,
    /** The package manager inside this environment. */
    val packageManager: PackageManager,
    /** Where the image comes from. */
    val archive: Archive,
    /** Rough extracted size, for the install confirmation. */
    val extractedMb: Int,
    /** One line in Settings: what this environment is for. */
    val purpose: String,
    /**
     * Whether the image ships inside the APK. False means it is downloaded on
     * first use — see the class comment for why the split falls here.
     */
    val bundled: Boolean,
) {
    /**
     * Alpine: the default. 3.9 MB, boots in seconds, musl.
     *
     * `bundled = true` because it is the environment every user gets and the
     * one the app is unusable without.
     */
    ALPINE(
        id = "alpine",
        displayName = "Alpine",
        dirName = "alpine-rootfs",
        packageManager = PackageManager.APK,
        archive = Archive.BundledAsset("alpine-minirootfs.tar.gz", "alpine-minirootfs.tar"),
        extractedMb = 50,
        purpose = "Küçük ve hızlı. Ajanın varsayılan ortamı; musl tabanlı.",
        bundled = true,
    ),

    /**
     * Debian: glibc, for the binaries Alpine cannot run.
     *
     * `bundled = false`: 95 MB compressed, and only worth it for the tasks that
     * need it. The URL is the LXC image index, which publishes a dated build
     * every day; [Archive.RemoteArchive.pinnedUrl] is used when set so a build
     * is reproducible, and the rolling URL only when the pin is cleared.
     */
    DEBIAN(
        id = "debian",
        displayName = "Debian",
        dirName = "debian-rootfs",
        packageManager = PackageManager.APT,
        archive = Archive.RemoteArchive(
            rollingUrl = "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/",
            pinnedUrl = "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20261115_05%3A24/rootfs.tar.xz",
            fileName = "rootfs.tar.xz",
        ),
        extractedMb = 350,
        purpose = "glibc tabanlı. Alpine'de çalışmayan hazır ikililer için.",
        bundled = false,
    ),
    ;

    /** Where this environment's root lives. */
    fun dir(filesDir: File): File = File(File(filesDir, "rootfs"), dirName)

    companion object {
        val DEFAULT: SandboxEnvironment = ALPINE

        fun byId(raw: String?): SandboxEnvironment? =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() }

        /** The environment a `/bin/sh` inside this rootfs would be. */
        fun shellOf(env: SandboxEnvironment): String = when (env.packageManager) {
            PackageManager.APK -> "/bin/sh"
            PackageManager.APT -> "/bin/sh"
        }
    }
}

/** The package manager a rootfs speaks. */
enum class PackageManager(
    /** The binary that drives it. */
    val tool: String,
    /** Install one or more packages. */
    val installCommand: (List<String>) -> String,
    /** Remove packages. */
    val removeCommand: (List<String>) -> String,
    /** Refresh the index. */
    val updateCommand: String,
) {
    APK(
        tool = "apk",
        installCommand = { pkgs -> "apk update >/dev/null 2>&1; apk add --no-cache ${pkgs.joinToString(" ")} 2>&1" },
        removeCommand = { pkgs -> "apk del ${pkgs.joinToString(" ")} 2>&1" },
        updateCommand = "apk update 2>&1",
    ),
    APT(
        tool = "apt-get",
        // DEBIAN_FRONTEND: without it, a package with a debconf prompt blocks
        // forever on a terminal that does not exist, and the install looks
        // like a hang rather than a failure.
        installCommand = { pkgs ->
            "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update >/dev/null 2>&1; " +
                "apt-get install -y --no-install-recommends ${pkgs.joinToString(" ")} 2>&1"
        },
        removeCommand = { pkgs ->
            "export DEBIAN_FRONTEND=noninteractive; apt-get remove -y ${pkgs.joinToString(" ")} 2>&1"
        },
        updateCommand = "export DEBIAN_FRONTEND=noninteractive; apt-get update 2>&1",
    ),
    ;
}

/**
 * Where an environment's image comes from, and how it must be opened.
 */
sealed interface Archive {
    /** The decompressed form this app can parse after unwrapping. */
    val innerFormat: String

    /** Ships in the APK's assets. */
    data class BundledAsset(
        /** Tried first; AAPT may have already decompressed it (see RootfsManager). */
        val gzName: String,
        /** Fallback when AAPT decompressed the asset. */
        val tarName: String,
    ) : Archive {
        override val innerFormat: String = "tar"
    }

    /** Downloaded on first use. */
    data class RemoteArchive(
        /** Directory listing to resolve the newest dated build, or a direct file. */
        val rollingUrl: String,
        /** A DATED url: reproducible, and what to prefer when set. */
        val pinnedUrl: String?,
        val fileName: String,
    ) : Archive {
        override val innerFormat: String =
            if (fileName.endsWith(".xz")) "tar.xz" else "tar.gz"

        /** The URL to actually fetch. */
        val url: String get() = pinnedUrl ?: rollingUrl
    }
}
