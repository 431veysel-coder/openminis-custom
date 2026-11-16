#!/usr/bin/env bash
# Identity guard — catches the failure mode where a package-name literal or a
# user-visible identity string is swapped to the wrong value.
#
# Our fork changes the applicationId but keeps the upstream namespace, so some
# literals must be the applicationId and others must stay the namespace.
# Getting one wrong compiles fine and breaks at runtime (it did:
# AppIconRepository builds activity-alias CLASS names from it).
#
# It also checks the identity a USER sees, which the first version did not:
# `app_name` overridden back to "Minis" in a locale file, or the versionName in
# build.gradle.kts drifting away from the release the tag claims. Both shipped
# once (22 locale files said "Minis"; main said 1.15-ozel.13 after that release
# had been withdrawn), and both are exactly the kind of thing that is invisible
# in a build log.
#
# Run from the repository root.
set -uo pipefail

APP_ID="com.veysel.minis"          # defaultConfig.applicationId
NAMESPACE="com.openminis.app"      # android { namespace }
RELEASE_REPO="431veysel-coder/openminis-custom"
APP_LABEL="Minis Özel"             # values/strings.xml app_name
M="src/android/app/src/main"
RES="$M/res"
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
  if grep -qF -- "$2" "$1" 2>/dev/null; then report ok "$1" "$3"; else report FAIL "$1" "$3"; fi
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

# --- user-visible identity -------------------------------------------------
# Every locale file that overrides app_name must say APP_LABEL. A locale file
# that says "Minis" makes the fork indistinguishable from the official app on
# the launcher, in the share sheet and in TalkBack — which is the one thing the
# fork exists to avoid.
default_name=$(sed -n 's/.*<string name="app_name">\([^<]*\)<\/string>.*/\1/p' "$RES/values/strings.xml" | head -1)
if [ "$default_name" = "$APP_LABEL" ]; then
  report ok "values/strings.xml app_name" "$APP_LABEL"
else
  report FAIL "values/strings.xml app_name" "expected '$APP_LABEL', found '$default_name'"
fi

while IFS= read -r f; do
  [ -n "$f" ] || continue
  name=$(sed -n 's/.*<string name="app_name">\([^<]*\)<\/string>.*/\1/p' "$f" | head -1)
  [ -n "$name" ] || continue   # locale does not override it: inherits the default, fine
  if [ "$name" = "$APP_LABEL" ]; then
    : # silent: there are 20+ of these and a line each would bury the failures
  else
    report FAIL "$f app_name" "found '$name' — must be '$APP_LABEL' or absent"
  fi
done < <(ls "$RES"/values*/strings.xml 2>/dev/null | grep -v "^$RES/values/strings.xml$")
if [ "$fail" -eq 0 ]; then report ok "locale app_name overrides" "all say $APP_LABEL"; fi

# --- self-update -----------------------------------------------------------
# Must point at OUR repo. The earlier check only proved it was not upstream,
# which a typo satisfies.
chk "$G" "RELEASE_REPO\", \"\\\"$RELEASE_REPO\\\"\"" "self-update repo"
if grep -qF -- 'RELEASE_REPO", "OpenMinis/OpenMinis' "$G" 2>/dev/null; then
  report FAIL "$G" "RELEASE_REPO still upstream — cannot install over ours"
fi

# --- version --------------------------------------------------------------
# versionName must carry the fork suffix, and versionCode must sit above the
# withdrawn 37013 range: an APK at or below it can never be updated onto a
# higher-numbered withdrawn build, and the number is burned.
vname=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$G" | head -1)
vcode=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$G" | head -1)
case "$vname" in
  *-ozel.*) report ok "$G versionName" "$vname" ;;
  *) report FAIL "$G versionName" "'$vname' does not carry the -ozel suffix" ;;
esac
if [ -n "$vcode" ] && [ "$vcode" -gt 37027 ]; then
  report ok "$G versionCode" "$vcode (> 37027)"
else
  report FAIL "$G versionCode" "'$vcode' must be > 37027 (37009-37013 and 37017-37027 are burned)"
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
