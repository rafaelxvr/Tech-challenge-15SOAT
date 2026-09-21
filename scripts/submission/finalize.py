#!/usr/bin/env python3
"""Turn the template manifest into the submission manifest and render the final PDF.

Everything except the demonstration video is already settled, so this script only
needs the published video URL and its measured duration. It records the reviewer
access that has already been granted, clears the pending evidence, switches the
manifest to submission mode and renders the PDF that goes to the student portal.

    python scripts/submission/finalize.py \\
        --video-url https://www.youtube.com/watch?v=XXXXXXXXXXX \\
        --duration-seconds 840
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
MANIFEST = HERE.parent.parent / "docs" / "phase-3" / "submission" / "submission-manifest.json"
DEFAULT_OUTPUT = HERE.parent.parent / "docs" / "phase-3" / "submission" / "phase3-submission.pdf"


def access_evidence(url: str) -> str:
    """The settings page that lists soat-architecture as an accepted collaborator."""
    return f"{url.rstrip('/')}/settings/access"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video-url", required=True,
                        help="Published YouTube or Vimeo URL, public or unlisted")
    parser.add_argument("--duration-seconds", required=True, type=int,
                        help="Measured video duration, 1..900")
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    if not 0 < args.duration_seconds <= 900:
        print("duration must be between 1 and 900 seconds", file=sys.stderr)
        return 1

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    manifest["submissionMode"] = "submission"
    manifest["status"] = "VERIFIED"
    manifest["videoUrl"] = args.video_url
    manifest["videoDurationSeconds"] = args.duration_seconds
    for repo in manifest["repositories"]:
        repo["reviewerAccessVerified"] = True
        repo["accessEvidence"] = access_evidence(repo["url"])
    manifest.pop("pendingEvidence", None)

    args.manifest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"manifest updated: {args.manifest}")

    result = subprocess.run(
        [sys.executable, str(HERE / "build_pdf.py"),
         "--manifest", str(args.manifest), "--output", str(args.output)],
        cwd=HERE,
    )
    if result.returncode != 0:
        return result.returncode
    print(f"submission PDF written: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
