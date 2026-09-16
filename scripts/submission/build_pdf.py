#!/usr/bin/env python3
import argparse
import json
import sys
from pathlib import Path

from check_submission import validate

PINNED_REPORTLAB_VERSION = "4.2.5"

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--allow-fixture", action="store_true")
    args = parser.parse_args()
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        validate(manifest, args.allow_fixture)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"PDF build refused: {error}", file=sys.stderr)
        return 1
    try:
        import reportlab
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import getSampleStyleSheet
        from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak
    except ModuleNotFoundError:
        print(f"PDF build refused: pinned local ReportLab {PINNED_REPORTLAB_VERSION} runtime is unavailable; create the local venv from scripts/submission/requirements-submission.txt before rendering.", file=sys.stderr)
        return 2
    if reportlab.Version != PINNED_REPORTLAB_VERSION:
        print(f"PDF build refused: ReportLab {reportlab.Version} differs from pinned {PINNED_REPORTLAB_VERSION}; use scripts/submission/requirements-submission.txt.", file=sys.stderr)
        return 2

    fixture = manifest["submissionMode"] == "fixture"
    styles = getSampleStyleSheet()
    story = [Paragraph("NON-SUBMISSION FIXTURE PDF" if fixture else "Phase 3 submission", styles["Title"])]
    story += [Paragraph(f"Release revision: {manifest['releaseRevision']}", styles["BodyText"]), Spacer(1, 12)]
    story += [Paragraph("Video", styles["Heading2"]), Paragraph(f'<link href="{manifest["videoUrl"]}">{manifest["videoUrl"]}</link> ({manifest["videoDurationSeconds"]} seconds)', styles["BodyText"]), Spacer(1, 12)]
    story += [Paragraph("Repositories and reviewer access", styles["Heading2"])]
    for repo in manifest["repositories"]:
        story.append(Paragraph(f'{repo["name"]}: <link href="{repo["url"]}">{repo["url"]}</link><br/>Access evidence: <link href="{repo["accessEvidence"]}">{repo["accessEvidence"]}</link>', styles["BodyText"]))
        story.append(Spacer(1, 6))
    story += [PageBreak(), Paragraph("Documentation", styles["Heading2"])]
    for url in manifest["documentationUrls"]: story.append(Paragraph(f'<link href="{url}">{url}</link>', styles["BodyText"]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    SimpleDocTemplate(str(args.output), pagesize=A4, title="Phase 3 submission").build(story)
    print(f"PDF built: {args.output} ({'fixture only' if fixture else 'verified submission'})")
    return 0

if __name__ == "__main__": sys.exit(main())
