#!/usr/bin/env python3
import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

NAMES = {"APP", "K8S", "FUN", "DB"}

def fail(message: str) -> None:
    raise ValueError(message)

def verified_url(value: object, label: str, fixture: bool) -> None:
    if not isinstance(value, str): fail(f"{label} is not a URL")
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.hostname: fail(f"{label} is not HTTPS with a nonempty host")
    host = parsed.hostname.lower()
    placeholder = host == "example.invalid" or any(token in host for token in ("placeholder", "not_provided", "todo", "localhost"))
    if fixture:
        if host != "example.invalid": fail(f"{label} fixture host must be example.invalid")
    elif placeholder:
        fail(f"{label} uses a placeholder host")

def validate(manifest: dict, allow_fixture: bool) -> None:
    fixture = manifest.get("submissionMode") == "fixture"
    if fixture and not allow_fixture:
        fail("fixture manifest requires --allow-fixture and is never submission-ready")
    if not fixture and manifest.get("submissionMode") != "submission":
        fail("manifest must declare submission mode after real publication/access verification")
    if manifest.get("reviewerUsername") != "soat-architecture": fail("reviewerUsername must be soat-architecture")
    if not re.fullmatch(r"[0-9a-f]{40}", str(manifest.get("releaseRevision", ""))): fail("releaseRevision must be an immutable 40-hex revision")
    repos = manifest.get("repositories")
    if not isinstance(repos, list) or len(repos) != 4: fail("repositories must contain exactly four records")
    if {repo.get("name") for repo in repos} != NAMES: fail("repository names must be exactly APP, K8S, FUN, DB")
    for repo in repos:
        verified_url(repo.get("url"), f"{repo.get('name')} URL", fixture)
        if repo.get("reviewerAccessVerified") is not True: fail(f"{repo.get('name')} reviewer access is unverified")
        verified_url(repo.get("accessEvidence"), f"{repo.get('name')} access evidence", fixture)
    verified_url(manifest.get("videoUrl"), "videoUrl", fixture)
    if not isinstance(manifest.get("videoDurationSeconds"), int) or not 0 < manifest["videoDurationSeconds"] <= 900: fail("videoDurationSeconds must be 1..900")
    docs = manifest.get("documentationUrls")
    if not isinstance(docs, list) or not docs: fail("documentationUrls must contain verified HTTPS links")
    for index, url in enumerate(docs): verified_url(url, f"documentationUrls[{index}]", fixture)
    if fixture and manifest.get("status") != "FIXTURE_ONLY": fail("fixture must be explicitly FIXTURE_ONLY")
    if not fixture and manifest.get("status") != "VERIFIED": fail("submission manifest must be VERIFIED")

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--allow-fixture", action="store_true")
    args = parser.parse_args()
    try:
        validate(json.loads(args.manifest.read_text(encoding="utf-8")), args.allow_fixture)
        if args.output and (not args.output.is_file() or args.output.read_bytes()[:5] != b"%PDF-"): fail("output PDF is absent or invalid")
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"Submission refused: {error}", file=sys.stderr)
        return 1
    print("PASS: fixture-only submission input" if args.allow_fixture else "PASS: verified submission input")
    return 0

if __name__ == "__main__": sys.exit(main())
