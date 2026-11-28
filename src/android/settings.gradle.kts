pluginManagement {
    repositories {
        google()
        // [H-013] Aynı gerekçe: eklenti çözümlemesi de 429'a takılıyordu.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // [H-013] Maven Central hız sınırı (429) derlemeyi "kod bozuk" gibi
        // gösteriyordu: `.54` test derlemesi ve `.58` APK derlemesi yalnız
        // `429 Too Many Requests` yüzünden düştü. Google'ın barındırdığı
        // **aynı içerikli** ayna önce denenir; bulamazsa istek normal
        // `mavenCentral()`e düşer. Sarmalayıcı (`gradle_retry.sh`) ikinci
        // savunma hattı, bu birinci.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
        }
        mavenCentral()
        // [T-android-vad] RealTimeCutVADLibraryForAndroid ships via JitPack
        // only. Same author and same underlying stack (Silero + ONNX Runtime +
        // WebRTC APM) as the RealTimeCutVADLibrary SPM package iOS already
        // uses, so both platforms segment speech with the same model and the
        // same tunables.
        maven { url = uri("https://jitpack.io") }
        // rclone.aar — the backup feature's remote destinations (SMB / WebDAV /
        // SFTP / S3 / FTP). Not published to any Maven repo: it is built from
        // deps/rclone-mobile by `deps/build_rclone_android.sh`, which is also
        // what produces the iOS XCFramework from the same Go sources and the
        // same trimmed backend list. Treated as a build artifact, not a vendored
        // binary — see docs/backup-restore-design.md §6.2.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Minis"
include(":app")
