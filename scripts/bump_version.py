#!/usr/bin/env python3
"""
OpenMinis Custom Version Bumper & Automation Script
Reads and updates versionCode and versionName in src/android/app/build.gradle.kts.
"""

import os
import re
import sys

GRADLE_PATH = os.path.join(
    os.path.dirname(__file__), "../src/android/app/build.gradle.kts"
)


def get_current_version():
    if not os.path.exists(GRADLE_PATH):
        print(f"Error: {GRADLE_PATH} not found.")
        return None, None

    with open(GRADLE_PATH, "r", encoding="utf-8") as f:
        content = f.read()

    vc_match = re.search(r"versionCode\s*=\s*(\d+)", content)
    vn_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)

    vc = int(vc_match.group(1)) if vc_match else None
    vn = vn_match.group(1) if vn_match else None
    return vc, vn


def bump_version(bump_type="patch"):
    vc, vn = get_current_version()
    if vc is None or vn is None:
        print("Could not parse version from build.gradle.kts")
        return

    print(f"Current version: versionCode={vc}, versionName={vn}")

    # Parse versionName (e.g., "1.0.4")
    parts = vn.split(".")
    major = int(parts[0]) if len(parts) > 0 else 1
    minor = int(parts[1]) if len(parts) > 1 else 0
    patch = int(parts[2]) if len(parts) > 2 else 0

    if bump_type == "major":
        major += 1
        minor = 0
        patch = 0
    elif bump_type == "minor":
        minor += 1
        patch = 0
    else:  # patch
        patch += 1

    new_vn = f"{major}.{minor}.{patch}"
    new_vc = vc + 1

    with open(GRADLE_PATH, "r", encoding="utf-8") as f:
        content = f.read()

    content = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {new_vc}", content)
    content = re.sub(
        r'versionName\s*=\s*"[^"]+"', f'versionName = "{new_vn}"', content
    )

    with open(GRADLE_PATH, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"Bumped version successfully! New version: versionCode={new_vc}, versionName={new_vn}")
    return new_vc, new_vn


if __name__ == "__main__":
    b_type = sys.argv[1] if len(sys.argv) > 1 else "patch"
    bump_version(b_type)
