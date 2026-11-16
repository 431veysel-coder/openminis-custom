#!/usr/bin/env python3
"""Run every auditor, in one pass, with one exit code.

[MINIS-OZEL 11.3.1] CI gate and pre-push gate share this entry point, so the
two can never disagree about what "clean" means.

    python3 scripts/audit/audit_all.py                 # gate
    python3 scripts/audit/audit_all.py --update-baseline
    python3 scripts/audit/audit_all.py --report out.json
"""

from __future__ import annotations

import argparse
import datetime
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
AUDITORS = [
    ("security", HERE / "security_audit.py"),
    ("code", HERE / "code_audit.py"),
    ("compliance", HERE / "compliance_audit.py"),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--update-baseline", action="store_true")
    ap.add_argument("--report", help="write a combined JSON report here")
    args = ap.parse_args()

    # The auditors must prove they still match before their verdict is trusted.
    print("self-test:", " ".join([sys.executable, str(HERE / "self_test.py")]))
    st = subprocess.run([sys.executable, str(HERE / "self_test.py")],
                        capture_output=True, text=True)
    if st.returncode != 0:
        print(st.stdout)
        print(st.stderr, file=sys.stderr)
        print("audit: self-test failed — refusing to report a verdict from a "
              "scanner that may have stopped matching", file=sys.stderr)
        return 1
    print(f"self-test: {st.stdout.strip().splitlines()[-1]}\n")

    overall = 0
    sections: dict[str, list] = {}
    for name, script in AUDITORS:
        cmd = [sys.executable, str(script)]
        json_path = HERE / f".{name}.json"
        cmd += ["--json", str(json_path)]
        if args.update_baseline:
            cmd.append("--update-baseline")
        result = subprocess.run(cmd, capture_output=True, text=True)
        sys.stdout.write(result.stdout)
        if result.stderr:
            sys.stderr.write(result.stderr)
        if result.returncode != 0:
            overall = 1
        if json_path.exists():
            try:
                sections[name] = json.loads(json_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                pass
            json_path.unlink()

    if args.report:
        payload = {
            "generated_utc": datetime.datetime.now(datetime.timezone.utc)
                            .isoformat(timespec="seconds"),
            "sections": sections,
            "verdict": "fail" if overall else "pass",
        }
        Path(args.report).write_text(
            json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        print(f"\naudit: report written to {args.report}")

    print("\naudit: " + ("FAILED" if overall else "PASSED"))
    return overall


if __name__ == "__main__":
    sys.exit(main())
