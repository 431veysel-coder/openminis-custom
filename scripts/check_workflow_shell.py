#!/usr/bin/env python3
"""Her `run:` bloğunu bash -n ile sözdizimi kontrolünden geçir.

Neden: shell `bash --noprofile --norc -eo pipefail {0}` yapıldıktan sonra
adımların hiçbiri yerelde koşulmadı. `bash -n` çalıştırmadan sözdizimi
hatasını yakalar; pipefail/SIGPIPE davranışını yakalamaz ama `bash -n`in
yakaladığı her hata CI'da 10 dakika sonra kırmızı olurdu.
"""
import subprocess
import sys
import tempfile
import pathlib

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[1]
wf = ROOT / ".github/workflows/apk.yml"
data = yaml.safe_load(wf.read_text())

shell = data["defaults"]["run"]["shell"]
assert shell.startswith("bash"), shell
flags = shell.replace("{0}", "").split()

bad = 0
checked = 0
for job, spec in data["jobs"].items():
    for step in spec["steps"]:
        if "run" not in step:
            continue
        script = step["run"]
        name = f"{job} / {step.get('name', '(unnamed)')}"
        checked += 1
        with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False) as fh:
            fh.write(script)
            path = fh.name
        r = subprocess.run(["bash", "-n", path], capture_output=True, text=True)
        if r.returncode != 0:
            bad += 1
            print(f"SYNTAX FAIL {name}\n{r.stderr}")

# The load-bearing assertion: no early-exit pipe consumers left in run blocks.
BANNED = ("| head", "| grep -q")
for job, spec in data["jobs"].items():
    for step in spec["steps"]:
        for line in (step.get("run") or "").splitlines():
            stripped = line.strip()
            if stripped.startswith("#"):
                continue
            for b in BANNED:
                if b in stripped:
                    print(f"BANNED PIPE {job} / {step.get('name')}: {stripped}")
                    bad += 1

print(f"checked {checked} run blocks, {bad} problem(s)")
sys.exit(1 if bad else 0)
