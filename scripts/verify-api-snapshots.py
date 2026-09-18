#!/usr/bin/env python3
"""Verify the Phase 3 API exports are exact, credential-free revision snapshots."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVISION = "7ca6e2948e423ea171c252eddeaca266179bd153"
SNAPSHOTS = {
    "docs/openapi.yaml": "docs/phase-3/api/openapi-7ca6e294.yaml",
    "postman/Oficina-Mecanica.postman_collection.json": "docs/phase-3/api/postman-7ca6e294.json",
}

for source, snapshot in SNAPSHOTS.items():
    expected = subprocess.run(
        ["git", "show", f"{REVISION}:{source}"], cwd=ROOT, check=True, capture_output=True
    ).stdout
    actual = (ROOT / snapshot).read_bytes()
    if actual != expected:
        raise SystemExit(f"snapshot differs from {REVISION}: {snapshot}")
    print(f"PASS: {snapshot} sha256={hashlib.sha256(actual).hexdigest()}")

try:
    json.loads((ROOT / SNAPSHOTS["postman/Oficina-Mecanica.postman_collection.json"]).read_text(encoding="utf-8"))
except json.JSONDecodeError as error:
    raise SystemExit(f"invalid Postman JSON: {error}")
