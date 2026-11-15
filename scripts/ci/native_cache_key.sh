#!/bin/sh
# [MINIS-OZEL] Deterministic cache key for the native build artifacts.
#
# The APK pipeline rebuilds proot, the Alpine rootfs and rclone.aar from source
# on every push. None of them depends on the Kotlin code, so a commit that only
# touches src/android/... still pays the full native cost — roughly half of the
# 21-minute pipeline.
#
# A cache key must include everything that can change the output, and nothing
# that changes on every commit, or the cache never hits. That is exactly:
#
#   * deps/proot's gitlink SHA — the proot sources
#   * deps/talloc version + the build script's own contents
#   * scripts/prepare_android_sandbox.sh — which packages go into the rootfs
#   * the pinned Alpine rootfs URL/version (grep'd out of the script)
#   * deps/rclone-mobile's gitlink SHA + the rclone build script
#   * the NDK version, which the workflow pins in env
#   * NATIVE_CACHE_VERSION — a manual salt
#
# Not included, deliberately: the commit SHA, the branch, the Kotlin sources.
# Including those would make every commit a miss, which is the current
# behaviour with extra steps.
#
# Prints a single line: the hex digest. Callers prefix it with a namespace.

set -eu

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

# Salt: bump when the artifact layout changes in a way the hashes below cannot
# see (a new file to cache, a path that moved). Bumping it invalidates every
# stored cache once, which is the correct trade — a stale cache here means a
# silently wrong sandbox in the shipped APK.
SALT="${NATIVE_CACHE_VERSION:-1}"

submodule_sha() {
    # Works without the submodule checked out: the gitlink is in the tree.
    git ls-tree HEAD "$1" 2>/dev/null | awk '{print $3}' || true
}

PROOT_SHA=$(submodule_sha deps/proot)
RCLONE_SHA=$(submodule_sha deps/rclone-mobile)
NDK="${NDK_VERSION:-unset}"

# Pin the Alpine rootfs coordinates, which live inside the prep script.
ALPINE_PIN=$(grep -Ei 'ALPINE_(VERSION|MIRROR|ROOTFS)|alpine-minirootfs' \
    scripts/prepare_android_sandbox.sh 2>/dev/null | sha256sum | awk '{print $1}')

{
    echo "salt=$SALT"
    echo "ndk=$NDK"
    echo "proot_sha=$PROOT_SHA"
    echo "rclone_sha=$RCLONE_SHA"
    echo "alpine_pin=$ALPINE_PIN"
    for f in deps/build_proot.sh deps/build_rclone_android.sh \
             scripts/prepare_android_sandbox.sh deps/build_ffmpeg.sh; do
        if [ -f "$f" ]; then
            echo "$f=$(sha256sum "$f" | awk '{print $1}')"
        fi
    done
} | sha256sum | cut -c1-24
