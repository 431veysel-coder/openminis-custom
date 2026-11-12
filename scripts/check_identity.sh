#!/usr/bin/env bash
# Identity guard — catches the failure mode where a package-name literal is
# swapped to the wrong identifier. Our fork changes the applicationId but keeps
# the upstream namespace, so some literals must be the applicationId and others
# must stay the namespace. Getting one wrong compiles fine and breaks at runtime
# (it did: AppIconRepository builds activity-alias CLASS names from it).
#
# Run from the repository root.
set -uo pipefail

APP_ID="com.veysel.minis"          # defaultConfig.applicationId
NAMESPACE="com.openminis.app"      # android { namespace }
M="src/android/app/src/main"
G="src/android/app/build.gradle.kts"
fail=0

report() { # report <ok|FAIL> <what> <why>
  if [ "$1" = ok ]; then
    printf '  ok    %-56s %s\n' "$2" "$3"
  else
    printf '  FAIL  %-56s %s\n' "$2" "$3"
    fail=1
  fi
}

chk() { # chk <path> <must-contain> <why>
  if rg -q -F "$2" "$1" 2>/dev/null; then report ok "$1" "$3"; else report FAIL "$1" "$3"; fi
}

echo "identity guard: applicationId=$APP_ID  namespace=$NAMESPACE"

# must reference the NAMESPACE (Java class / component names)
chk "$M/java/com/openminis/app/data/repository/AppIconRepository.kt" \
    "private const val PACKAGE_NAME = \"$NAMESPACE\"" "icon-alias CLASS prefix"
chk "$M/java/com/openminis/app/diagnostics/HangDetector.kt" \
    "APP_FRAME_PREFIX = \"$NAMESPACE.\"" "stack-frame attribution prefix"

# must reference the APPLICATION_ID (package as PackageManager sees it)
chk "$M/java/com/openminis/app/accessibility/MinisAccessibilityService.kt" \
    "SERVICE_ID = \"$APP_ID/.accessibility.MinisAccessibilityService\"" \
    "enabled_accessibility_services component id"
chk "$M/java/com/openminis/app/diagnostics/ResourceWatermark.kt" \
    "indexOf(\"/$APP_ID/\")" "/data/user/0/<appId>/ path matcher"
chk "$M/res/xml/shortcuts.xml" "android:targetPackage=\"$APP_ID\"" \
    "launcher shortcut target package"
chk "$G" "applicationId = \"$APP_ID\"" "applicationId"
chk "$G" "namespace = \"$NAMESPACE\"" "namespace"

# self-update must not point upstream: that APK is signed with a different key
if rg -q 'RELEASE_REPO", "OpenMinis/OpenMinis' "$G" 2>/dev/null; then
  report FAIL "$G" "RELEASE_REPO still upstream — cannot install over ours"
else
  report ok "$G" "RELEASE_REPO points at our fork"
fi

# native loaders: absent means every shell command returns "[Shell not running] -1"
for so in libproot-loader.so libproot-loader32.so; do
  if [ -f "$M/jniLibs/arm64-v8a/$so" ]; then
    report ok "jniLibs/$so" "present"
  else
    echo "  skip  jniLibs/$so (produced by deps/build_proot.sh)"
  fi
done

[ "$fail" -eq 0 ] && { echo "identity guard passed"; exit 0; }
echo "identity guard FAILED"; exit 1
