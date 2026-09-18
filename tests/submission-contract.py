"""Offline manifest, linked PDF and preview refusal contracts; never contacts URLs."""
import copy
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECK = ROOT / "scripts/submission/check_submission.py"
BUILD = ROOT / "scripts/submission/build_pdf.py"
TEMPLATE = ROOT / "docs/phase-3/submission/submission-manifest.json"
FIXTURE = ROOT / "tests/fixtures/submission-fixture.json"
checks = 0

def run(*args):
    return subprocess.run([sys.executable, *map(str, args)], capture_output=True, text=True)

def require(result, code, phrase):
    global checks
    if result.returncode != code or phrase not in result.stdout + result.stderr:
        raise AssertionError(result.stdout + result.stderr)
    checks += 1

require(run(CHECK, "--manifest", TEMPLATE), 1, "requires --allow-template")
require(run(CHECK, "--manifest", FIXTURE), 1, "requires --allow-fixture")
require(run(CHECK, "--manifest", FIXTURE, "--allow-fixture"), 0, "PASS: fixture-only")
require(run(CHECK, "--manifest", TEMPLATE, "--allow-template"), 0, "PASS: template-only")
with tempfile.TemporaryDirectory() as directory:
    directory = Path(directory)
    changed = directory / "changed.json"
    template = json.loads(TEMPLATE.read_text())
    fixture = json.loads(FIXTURE.read_text())
    for field, value in [("releaseRevision", "a" * 40), ("videoDurationSeconds", 840), ("status", "VERIFIED")]:
        invalid = copy.deepcopy(template)
        invalid[field] = value
        changed.write_text(json.dumps(invalid))
        require(run(CHECK, "--manifest", changed, "--allow-template"), 1, "Submission refused")
    for mutation in [
        lambda m: m["repositories"][0].update(reviewerAccessVerified=True),
        lambda m: m["repositories"][0].update(accessEvidence="https://github.com/reviewer/access"),
        lambda m: m["repositories"][0].update(reviewedRevision=None),
        lambda m: m["repositories"][0].update(reviewedRevision=[]),
        lambda m: m["repositories"][0].update(url=m["repositories"][1]["url"]),
        lambda m: m["repositories"][0].update(url="https://example.invalid/unknown"),
        lambda m: m.update(videoUrl="https://videos.example.org/not-recorded"),
        lambda m: m.update(pendingEvidence=[None]),
        lambda m: m.update(documentationUrls=["http://github.com/unreviewed"]),
    ]:
        invalid = copy.deepcopy(template)
        mutation(invalid)
        changed.write_text(json.dumps(invalid))
        require(run(CHECK, "--manifest", changed, "--allow-template"), 1, "Submission refused")
    blank = copy.deepcopy(template)
    blank.update(releaseRevision="NOT_CAPTURED", documentationUrls=[])
    for repo in blank["repositories"]:
        repo.pop("reviewedRevision")
        repo["url"] = "NOT_PROVIDED"
    changed.write_text(json.dumps(blank))
    require(run(CHECK, "--manifest", changed, "--allow-template"), 0, "PASS: template-only")
    pending_fixture = copy.deepcopy(fixture)
    pending_fixture["pendingEvidence"] = ["R4 pending"]
    changed.write_text(json.dumps(pending_fixture))
    require(run(CHECK, "--manifest", changed, "--allow-fixture"), 1, "pending evidence")
    for field, value in [("schemaVersion", True), ("releaseRevision", []), ("videoDurationSeconds", True),
                         ("videoDurationSeconds", 901), ("repositories", []), ("documentationUrls", [])]:
        invalid = copy.deepcopy(fixture)
        invalid[field] = value
        changed.write_text(json.dumps(invalid))
        require(run(CHECK, "--manifest", changed, "--allow-fixture"), 1, "Submission refused")
    for mutation in [
        lambda m: m["repositories"][0].update(reviewerAccessVerified=False),
        lambda m: m["repositories"][0].update(url=m["repositories"][1]["url"]),
        lambda m: m["repositories"][0].update(url="https://user:password@example.invalid/repo"),
    ]:
        invalid = copy.deepcopy(fixture)
        mutation(invalid)
        changed.write_text(json.dumps(invalid))
        require(run(CHECK, "--manifest", changed, "--allow-fixture"), 1, "Submission refused")
    invalid = copy.deepcopy(fixture)
    invalid.update(submissionMode="submission", status="VERIFIED")
    changed.write_text(json.dumps(invalid))
    require(run(CHECK, "--manifest", changed), 1, "placeholder host")
    changed.write_text(json.dumps(fixture).replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1'))
    require(run(CHECK, "--manifest", changed, "--allow-fixture"), 1, "duplicate manifest field")

    template_pdf = directory / "template.pdf"
    fixture_pdf = directory / "fixture.pdf"
    require(run(BUILD, "--manifest", TEMPLATE, "--output", template_pdf), 1, "requires --allow-template")
    assert not template_pdf.exists()
    require(run(BUILD, "--manifest", TEMPLATE, "--allow-template", "--output", template_pdf), 0, "PDF built and checked")
    require(run(CHECK, "--manifest", TEMPLATE, "--allow-template", "--output", template_pdf, "--render-pages", directory / "pages"), 0, "PASS: template-only")
    from pypdf import PdfReader
    assert len(list((directory / "pages").glob("page-*.png"))) == len(PdfReader(template_pdf).pages)
    original_bytes = template_pdf.read_bytes()
    require(run(BUILD, "--manifest", TEMPLATE, "--allow-template", "--output", template_pdf), 0, "PDF built and checked")
    assert template_pdf.read_bytes() == original_bytes, "Same template must reproduce identical PDF bytes"
    # Exercise escaped clickable links using only reserved, non-resolving fixture URLs.
    fixture["videoUrl"] = "https://example.invalid/video?one=1&two=2"
    changed.write_text(json.dumps(fixture))
    require(run(BUILD, "--manifest", changed, "--allow-fixture", "--output", fixture_pdf), 0, "PDF built and checked")
    require(run(CHECK, "--manifest", changed, "--allow-fixture", "--output", fixture_pdf), 0, "PASS: fixture-only")
    require(run(CHECK, "--manifest", TEMPLATE, "--allow-template", "--output", fixture_pdf), 1, "not bound to this manifest")
    fake_pdf = directory / "fake.pdf"
    fake_pdf.write_bytes(b"%PDF-1.7\nnot an actual PDF")
    require(run(CHECK, "--manifest", TEMPLATE, "--allow-template", "--output", fake_pdf), 1, "Submission refused")
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import NameObject, TextStringObject
    reader = PdfReader(fixture_pdf)
    writer = PdfWriter()
    writer.clone_document_from_reader(reader)
    annotation = writer.pages[0]["/Annots"][0].get_object()
    annotation["/A"][NameObject("/URI")] = TextStringObject("https://example.invalid/tampered")
    tampered_pdf = directory / "tampered.pdf"
    with tampered_pdf.open("wb") as handle:
        writer.write(handle)
    require(run(CHECK, "--manifest", changed, "--allow-fixture", "--output", tampered_pdf), 1, "clickable links differ")

print(f"PASS: {checks} offline submission checks, all-page rendering, exact PDF links and deterministic NOT_READY template.")
