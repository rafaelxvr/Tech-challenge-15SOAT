import subprocess
import sys
import tempfile
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECK = ROOT / "scripts/submission/check_submission.py"
BUILD = ROOT / "scripts/submission/build_pdf.py"
TEMPLATE = ROOT / "docs/phase-3/submission/submission-manifest.json"
FIXTURE = ROOT / "tests/fixtures/submission-fixture.json"

def run(*args): return subprocess.run([sys.executable, *map(str, args)], capture_output=True, text=True)
def require(result, code, phrase):
    if result.returncode != code or phrase not in (result.stdout + result.stderr): raise SystemExit(result.stdout + result.stderr)

require(run(CHECK, "--manifest", TEMPLATE), 1, "Submission refused")
require(run(CHECK, "--manifest", FIXTURE), 1, "requires --allow-fixture")
require(run(CHECK, "--manifest", FIXTURE, "--allow-fixture"), 0, "PASS: fixture-only")
with tempfile.TemporaryDirectory() as directory:
    output = Path(directory) / "fixture.pdf"
    hostile = json.loads(FIXTURE.read_text())
    hostile["submissionMode"] = "submission"
    hostile["status"] = "VERIFIED"
    hostile_path = Path(directory) / "placeholder-submission.json"
    hostile_path.write_text(json.dumps(hostile))
    require(run(CHECK, "--manifest", hostile_path), 1, "placeholder host")
    hostile["repositories"][0]["url"] = "https://"
    hostile_path.write_text(json.dumps(hostile))
    require(run(CHECK, "--manifest", hostile_path), 1, "nonempty host")
    require(run(CHECK, "--manifest", FIXTURE, "--allow-fixture", "--output", output), 1, "output PDF is absent")
    result = run(BUILD, "--manifest", FIXTURE, "--allow-fixture", "--output", output)
    if result.returncode == 0:
        require(run(CHECK, "--manifest", FIXTURE, "--allow-fixture", "--output", output), 0, "PASS: fixture-only")
    else:
        require(result, 2, "pinned local ReportLab 4.2.5 runtime is unavailable")
print("PASS: submission template is unverified and fixture rendering is locally gated.")
