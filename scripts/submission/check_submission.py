#!/usr/bin/env python3
"""Offline structure/PDF checks. External publication and access are never inferred."""
import argparse
import hashlib
import importlib.metadata
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

NAMES = {"APP", "K8S", "FUN", "DB"}
FIELDS = {"schemaVersion", "submissionMode", "releaseRevision", "reviewerUsername", "repositories",
          "videoUrl", "videoDurationSeconds", "documentationUrls", "status"}
REPO_FIELDS = {"name", "url", "reviewerAccessVerified", "accessEvidence"}
PINS = {"reportlab": "4.4.9", "pypdf": "6.10.0", "pypdfium2": "5.13.0"}

def fail(message):
    raise ValueError(message)

def require_runtime(name):
    try:
        version = importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        fail(f"pinned local {name} {PINS[name]} runtime is unavailable")
    if version != PINS[name]:
        fail(f"{name} {version} differs from pinned {PINS[name]}")

def read_manifest(path):
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail("duplicate manifest field")
            result[key] = value
        return result
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_pairs,
                      parse_constant=lambda _: fail("non-finite JSON value"))

def manifest_digest(manifest):
    return hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

def verified_url(value, label, fixture):
    if not isinstance(value, str):
        fail(f"{label} is not a URL")
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or any(c.isspace() for c in value):
        fail(f"{label} is not HTTPS with a nonempty host and no embedded credentials")
    host = parsed.hostname.lower()
    placeholder = host.endswith((".invalid", ".test", ".localhost")) or host in {"example.com", "example.org", "example.net", "localhost"} or any(token in host for token in ("placeholder", "not_provided", "todo"))
    if fixture and host != "example.invalid":
        fail(f"{label} fixture host must be example.invalid")
    if not fixture and placeholder:
        fail(f"{label} uses a placeholder host")

def validate(manifest, allow_fixture=False, allow_template=False):
    if not isinstance(manifest, dict) or set(manifest) not in (FIELDS, FIELDS | {"pendingEvidence"}) or type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1:
        fail("manifest must have the exact schema-v1 object fields")
    mode = manifest["submissionMode"]
    if mode == "fixture" and not allow_fixture:
        fail("fixture manifest requires --allow-fixture and is never submission-ready")
    if mode == "template" and not allow_template:
        fail("template requires --allow-template and remains NOT_READY")
    if mode not in {"template", "fixture", "submission"}:
        fail("unknown submission mode")
    if manifest["reviewerUsername"] != "soat-architecture":
        fail("reviewerUsername must be soat-architecture")
    repos = manifest["repositories"]
    if not isinstance(repos, list) or len(repos) != 4 or any(not isinstance(r, dict) or set(r) not in (REPO_FIELDS, REPO_FIELDS | {"reviewedRevision"}) for r in repos):
        fail("repositories must contain exactly four complete records")
    if any(not isinstance(r["name"], str) for r in repos) or {r["name"] for r in repos} != NAMES:
        fail("repository names must be exactly APP, K8S, FUN, DB")
    if any("reviewedRevision" in r for r in repos):
        if any(not isinstance(r.get("reviewedRevision"), str) or not re.fullmatch(r"[0-9a-f]{40}", r["reviewedRevision"]) for r in repos):
            fail("reviewed source references require four immutable revisions")
        if next(r["reviewedRevision"] for r in repos if r["name"] == "APP") != manifest["releaseRevision"]:
            fail("APP reviewed revision must match releaseRevision")
    pending = manifest.get("pendingEvidence", [])
    if not isinstance(pending, list) or any(not isinstance(item, str) or not item.strip() for item in pending):
        fail("pendingEvidence must be a list of nonempty strings")
    if mode != "template" and pending:
        fail("pending evidence cannot pass fixture or submission readiness")
    if mode == "template":
        revision = manifest["releaseRevision"]
        if not isinstance(revision, str) or (revision != "NOT_CAPTURED" and not re.fullmatch(r"[0-9a-f]{40}", revision)):
            fail("template release reference must be unknown or an immutable source revision")
        if manifest["status"] != "NOT_READY" or manifest["videoUrl"] != "NOT_PROVIDED" or manifest["videoDurationSeconds"] is not None:
            fail("template must remain NOT_READY with video and duration unrecorded")
        if any(r["accessEvidence"] != "NOT_PROVIDED" or r["reviewerAccessVerified"] is not False for r in repos):
            fail("template must not claim reviewer access")
        urls = [r["url"] for r in repos if r["url"] != "NOT_PROVIDED"]
        if len(set(urls)) != len(urls):
            fail("provided repository URLs must be distinct")
        docs = manifest["documentationUrls"]
        if not isinstance(docs, list):
            fail("template documentationUrls must be a list")
        for url in urls + docs:
            verified_url(url, "template source reference", False)
        return
    fixture = mode == "fixture"
    if not isinstance(manifest["releaseRevision"], str) or not re.fullmatch(r"[0-9a-f]{40}", manifest["releaseRevision"]):
        fail("releaseRevision must be an immutable 40-hex revision")
    for repo in repos:
        verified_url(repo["url"], f"{repo['name']} URL", fixture)
        if repo["reviewerAccessVerified"] is not True:
            fail(f"{repo['name']} reviewer access is unverified")
        verified_url(repo["accessEvidence"], f"{repo['name']} access evidence", fixture)
    if len({r["url"] for r in repos}) != 4:
        fail("repository URLs must be distinct")
    verified_url(manifest["videoUrl"], "videoUrl", fixture)
    if type(manifest["videoDurationSeconds"]) is not int or not 0 < manifest["videoDurationSeconds"] <= 900:
        fail("videoDurationSeconds must be 1..900")
    docs = manifest["documentationUrls"]
    if not isinstance(docs, list) or not docs:
        fail("documentationUrls must contain verified HTTPS links")
    for index, url in enumerate(docs):
        verified_url(url, f"documentationUrls[{index}]", fixture)
    if manifest["status"] != ("FIXTURE_ONLY" if fixture else "VERIFIED"):
        fail("status does not match the explicit submission mode")

def expected_urls(manifest):
    if manifest["submissionMode"] == "template":
        return set()
    return {manifest["videoUrl"], *manifest["documentationUrls"],
            *(r[k] for r in manifest["repositories"] for k in ("url", "accessEvidence"))}

def pdf_marker(manifest):
    return "SUBMISSION METADATA" if manifest["submissionMode"] == "submission" else "NOT_READY / FIXTURE_ONLY"

def check_pdf(manifest, path):
    require_runtime("pypdf")
    from pypdf import PdfReader
    if not path.is_file() or path.read_bytes()[:5] != b"%PDF-":
        fail("output PDF is absent or invalid")
    try:
        pdf = PdfReader(path, strict=True)
        if pdf.is_encrypted or len(pdf.pages) < 2:
            fail("PDF must contain the complete readable sections")
        if pdf.metadata.subject != "manifest-sha256:" + manifest_digest(manifest):
            fail("PDF is not bound to this manifest")
        urls, texts = set(), []
        for page in pdf.pages:
            text = page.extract_text() or ""
            if pdf_marker(manifest) not in text:
                fail("each PDF page must disclose its submission status")
            texts.append(text)
            for reference in page.get("/Annots", []):
                annotation = reference.get_object()
                action = annotation.get("/A", {})
                if annotation.get("/Subtype") != "/Link" or action.get("/S") != "/URI":
                    fail("unexpected PDF link action")
                urls.add(str(action.get("/URI")))
        combined = "\n".join(texts)
        duration = "NOT_RECORDED" if manifest["submissionMode"] == "template" else str(manifest["videoDurationSeconds"]) + " seconds"
        for required in ["Video", "Documentation", "soat-architecture", manifest["releaseRevision"], "Recorded duration: " + duration, *["Repository " + name for name in sorted(NAMES)]]:
            if required not in combined:
                fail("PDF is missing a required entry")
        compact = re.sub(r"\s+", "", combined)
        for required in [*(r["reviewedRevision"] for r in manifest["repositories"] if "reviewedRevision" in r), *manifest.get("pendingEvidence", [])]:
            if re.sub(r"\s+", "", required) not in compact:
                fail("PDF is missing a reviewed source reference or pending evidence")
        if urls != expected_urls(manifest):
            fail("PDF clickable links differ from the manifest")
    except ValueError:
        raise
    except Exception:
        fail("output PDF cannot be parsed")

def render_pages(pdf_path, directory):
    require_runtime("pypdfium2")
    import pypdfium2
    directory.mkdir(parents=True, exist_ok=True)
    with pypdfium2.PdfDocument(str(pdf_path)) as pdf:
        for index in range(len(pdf)):
            page = pdf[index]
            try:
                bitmap = page.render(scale=1.4)
                try:
                    bitmap.to_pil().save(directory / f"page-{index + 1}.png")
                finally:
                    bitmap.close()
            finally:
                page.close()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    preview = parser.add_mutually_exclusive_group()
    preview.add_argument("--allow-fixture", action="store_true")
    preview.add_argument("--allow-template", action="store_true")
    parser.add_argument("--render-pages", type=Path)
    args = parser.parse_args()
    try:
        manifest = read_manifest(args.manifest)
        validate(manifest, args.allow_fixture, args.allow_template)
        if args.output:
            check_pdf(manifest, args.output)
        if args.render_pages:
            if not args.output:
                fail("--render-pages requires --output")
            render_pages(args.output, args.render_pages)
    except (OSError, ValueError, TypeError) as error:
        print(f"Submission refused: {error}", file=sys.stderr)
        return 1
    print("PASS: " + ("submission metadata; external access still requires manual verification" if manifest["submissionMode"] == "submission" else manifest["submissionMode"] + "-only NOT_READY input/PDF"))
    return 0

if __name__ == "__main__":
    sys.exit(main())
