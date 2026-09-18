#!/usr/bin/env python3
"""Render locally; templates and fixtures are visibly excluded from submission."""
import argparse
from html import escape
import sys
from pathlib import Path

from check_submission import validate, read_manifest, require_runtime, manifest_digest, pdf_marker, check_pdf

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    preview = parser.add_mutually_exclusive_group()
    preview.add_argument("--allow-fixture", action="store_true")
    preview.add_argument("--allow-template", action="store_true")
    args = parser.parse_args()
    try:
        manifest = read_manifest(args.manifest)
        validate(manifest, args.allow_fixture, args.allow_template)
        require_runtime("reportlab")
        require_runtime("pypdf")
    except (OSError, ValueError, TypeError) as error:
        print(f"PDF build refused: {error}", file=sys.stderr)
        return 1

    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak

    template = manifest["submissionMode"] == "template"
    preview_mode = manifest["submissionMode"] != "submission"
    styles = getSampleStyleSheet()
    styles.add(ParagraphStyle("Body", fontName="Helvetica", fontSize=10.5, leading=15, spaceAfter=9))
    styles["Title"].textColor = colors.HexColor("#16334a")
    styles["Title"].fontSize = 25
    styles["Heading2"].textColor = colors.HexColor("#136f73")
    styles["Heading2"].spaceBefore = 12

    def paragraph(text, style="Body"):
        return Paragraph(text, styles[style])

    def link(url):
        safe = escape(url, quote=True)
        return "NOT_PROVIDED - publication/access pending" if url == "NOT_PROVIDED" else f'<link href="{safe}" color="#136f73">{safe}</link>'

    def decorate(canvas, document):
        canvas.saveState()
        canvas.setTitle("Phase 3 - " + ("NOT_READY offline preview" if preview_mode else "Submission metadata"))
        canvas.setAuthor("Oficina Phase 3")
        canvas.setSubject("manifest-sha256:" + manifest_digest(manifest))
        canvas.setFillColor(colors.HexColor("#16334a"))
        canvas.setFont("Helvetica-Bold", 9)
        canvas.drawString(48, A4[1] - 32, pdf_marker(manifest))
        canvas.setStrokeColor(colors.HexColor("#b8c8d0"))
        canvas.line(48, 45, A4[0] - 48, 45)
        canvas.setFont("Helvetica", 8)
        canvas.drawString(48, 31, "Offline structure checks do not establish publication, reviewer access or deployment.")
        canvas.drawRightString(A4[0] - 48, 18, f"Page {document.page}")
        canvas.restoreState()

    story = [paragraph("Phase 3<br/>" + ("Offline submission template" if template else "Fixture layout test" if preview_mode else "Submission package"), "Title"), Spacer(1, 10)]
    if preview_mode:
        story.append(paragraph("<b>NOT_READY / FIXTURE_ONLY - NOT FOR PORTAL SUBMISSION</b>"))
        story.append(paragraph("Unknown fields below remain unfilled." if template else "Every URL, revision, duration and access assertion in this fixture is synthetic test data."))
    story.append(paragraph("Release revision: " + escape(manifest["releaseRevision"])))
    story.append(paragraph("Reviewer identity required: <b>soat-architecture</b>. Access is " + ("NOT VERIFIED." if template else "a supplied assertion requiring a separate manual evidence audit.")))
    story.append(paragraph("Video", "Heading2"))
    duration = "NOT_RECORDED" if template else str(manifest["videoDurationSeconds"]) + " seconds"
    story.append(paragraph(link(manifest["videoUrl"]) + "<br/>Recorded duration: " + duration))
    story.append(paragraph("Repositories and reviewer access", "Heading2"))
    for repo in manifest["repositories"]:
        story.append(paragraph("Repository " + escape(repo["name"]) + "<br/>" + link(repo["url"]) +
                               "<br/>Access evidence: " + link(repo["accessEvidence"])))
    story += [PageBreak(), paragraph("Documentation and release context", "Title"), paragraph("Documentation", "Heading2")]
    if manifest["documentationUrls"]:
        story.extend(paragraph(link(url)) for url in manifest["documentationUrls"])
    else:
        story.append(paragraph("NOT_PROVIDED - published architecture, API, ADR/RFC and evidence URLs must be supplied after review."))
    story.append(paragraph("Recording plan", "Heading2"))
    story.append(paragraph("Target: 14 minutes. Maximum: 15 minutes. This is a planned schedule, not a measured video duration."))
    for line in [
        "00:00-01:30 - architecture and ownership",
        "01:30-05:30 - authentication and orders",
        "05:30-08:30 - CI/CD and immutable release records",
        "08:30-12:30 - dashboards, traces, failure and recovery",
        "12:30-14:00 - data, documentation and actual results",
    ]:
        story.append(paragraph(line))
    story.append(paragraph("Before a real submission", "Heading2"))
    story.append(paragraph("Supply the tested release revision, four published repository URLs, confirmed reviewer access evidence, playable video URL and measured duration, and published documentation URLs. A pending invitation or login page is not proof of access."))
    story.append(paragraph("Review actual deployment records separately. Disclose recorded/time-compressed demonstrations and every NOT_RUN result. This PDF neither promotes acceptance records nor authorizes upload, invitations or portal submission."))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        SimpleDocTemplate(str(args.output), pagesize=A4, leftMargin=48, rightMargin=48, topMargin=58, bottomMargin=60, invariant=1).build(
            story, onFirstPage=decorate, onLaterPages=decorate)
        check_pdf(manifest, args.output)
    except Exception as error:
        print(f"PDF build/check refused: {error}", file=sys.stderr)
        return 1
    print(f"PDF built and checked: {args.output} ({pdf_marker(manifest)})")
    return 0

if __name__ == "__main__":
    sys.exit(main())
