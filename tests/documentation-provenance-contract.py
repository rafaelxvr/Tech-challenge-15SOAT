"""Offline current-source consistency; historical execution and readiness stay separate."""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--app-revision", required=True)
parser.add_argument("--k8s-revision", required=True)
args = parser.parse_args()
expected = {"APP": args.app_revision, "K8S": args.k8s_revision}
assert all(re.fullmatch(r"[a-f0-9]{40}", value) for value in expected.values())

audit = (ROOT / "docs/phase-3/evidence/implementation-audit.md").read_text(encoding="utf-8")
readme = (ROOT / "docs/phase-3/submission/README.md").read_text(encoding="utf-8")
manifest = json.loads((ROOT / "docs/phase-3/submission/submission-manifest.json").read_text())
attempt = json.loads((ROOT / "docs/phase-3/evidence/app-staging-attempt-2026-09-19/attempt.json").read_text())
evidence = json.loads((ROOT / "docs/phase-3/evidence/manifest.json").read_text())


def validate(current_audit, current_readme, current_manifest):
    summary = next(line for line in current_audit.splitlines() if line.startswith("Current source references"))
    submission = next(line for line in current_readme.splitlines() if line.startswith("Current status:"))
    repos = {repo["name"]: repo for repo in current_manifest["repositories"]}
    for name, revision in expected.items():
        assert repos[name]["reviewedRevision"] == revision, f"{name} manifest revision drift"
        assert f"{name} `{revision}`" in summary, f"{name} audit summary drift"
        assert f"{name} `{revision}`" in submission, f"{name} submission README drift"
        rows = [line for line in current_audit.splitlines() if line.startswith(f"| {name} |")]
        assert len(rows) == 2 and all(f"| `{revision}` |" in row for row in rows), f"{name} audit table drift"
    assert current_manifest["releaseRevision"] == expected["APP"]
    assert f"/blob/{expected['K8S']}/docs/evidence/r4-local-status.json" in current_audit
    app_docs = [url for url in current_manifest["documentationUrls"] if "/Tech-challenge-15SOAT/blob/" in url]
    assert len(app_docs) == 2 and all(f"/blob/{expected['APP']}/" in url for url in app_docs)
    assert f"APP `{attempt['appSourceCommit']}` build" in current_audit, "Historical attempt attribution changed"
    assert f"at APP `{attempt['appSourceCommit'][:7]}`" in current_readme
    assert current_manifest["status"] == "NOT_READY" and current_manifest["submissionMode"] == "template"
    assert "**NOT_READY**" in submission
    assert all(repo["reviewerAccessVerified"] is False and repo["accessEvidence"] == "NOT_PROVIDED" for repo in repos.values())
    assert current_manifest["videoUrl"] == "NOT_PROVIDED" and current_manifest["videoDurationSeconds"] is None


validate(audit, readme, manifest)
assert evidence["status"] == "NOT_RUN" and len(evidence["records"]) == 8
for record in evidence["records"]:
    assert all(record[field] == "NOT_RUN" for field in ("result", "observedResult", "timestampUtc"))
    assert all(record[field] == "NOT_CAPTURED" for field in ("sourceRevision", "artifactDigest", "planRevision"))

# The checker must catch stale current links and accidental rewriting of a receipt.
for bad_audit, bad_readme in (
    (audit.replace(expected["K8S"], "0" * 40), readme),
    (audit, readme.replace(expected["APP"], "0" * 40)),
    (audit.replace(f"APP `{attempt['appSourceCommit']}` build", f"APP `{expected['APP']}` build"), readme),
):
    try:
        validate(bad_audit, bad_readme, manifest)
    except AssertionError:
        continue
    raise AssertionError("Negative provenance fixture was accepted")

print("PASS: current APP/K8S references agree; historical attempt, eight R4 rows and NOT_READY preserved; three negative cases rejected.")
