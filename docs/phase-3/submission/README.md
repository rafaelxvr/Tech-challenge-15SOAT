# Phase 3 offline submission preparation

Current status: **NOT_READY**. The [manifest template](submission-manifest.json) leaves repository/video/documentation URLs unprovided, access unverified, release revision uncaptured and measured video duration `null`. The [14-minute script](video-script.md) is a recording plan, not a recording. The [eight R4 records](../evidence/manifest.json) remain separate deployment acceptance evidence; a PDF never changes them.

## Local runtime and checks

Use the configured bundled Python runtime or a project-local virtual environment with the exact versions in [requirements-submission.txt](../../../scripts/submission/requirements-submission.txt): ReportLab 4.4.9 for PDF generation, pypdf 6.10.0 for parsing/content/link checks and pypdfium2 5.13.0 for rendering every page. No paid/cloud renderer is used. Do not install globally or silently accept a different runtime. A local alternative is `python -m venv .venv-submission`, then invoke that environment's Python with `-m pip install -r scripts/submission/requirements-submission.txt`.

From the APP root, run:

```text
python tests/submission-contract.py
python scripts/check-doc-links.py docs README.md
python scripts/submission/build_pdf.py --manifest docs/phase-3/submission/submission-manifest.json --allow-template --output artifacts/phase-3-submission.template.pdf
python scripts/submission/check_submission.py --manifest docs/phase-3/submission/submission-manifest.json --allow-template --output artifacts/phase-3-submission.template.pdf --render-pages artifacts/submission-template-pages
```

Without `--allow-template`, the committed template is rejected. Its generated PDF is watermarked `NOT_READY / FIXTURE_ONLY` on **every page**, has no external link annotations and cannot assert a release or reviewer access. The actual recorded duration stays `NOT_RECORDED`; the 14-minute target is explicitly labeled as a plan. Generated PDFs/page images stay under ignored `artifacts/`; regenerate them from the committed sources. Fixed PDF metadata makes the same template reproducible; metadata timestamps are not capture/deployment evidence.

The test-only `--allow-fixture` mode is separate. Its reserved `example.invalid` URLs, synthetic revision, duration and access assertions exist solely to exercise clickable-link rendering in a temporary directory. Never copy those values to the submission manifest. The tests prove that placeholders cannot pass submission mode, flags cannot validate incomplete real metadata, a `%PDF` header alone is insufficient, changed link targets are rejected and the PDF's manifest digest must match. They also render every template page; manually inspect the PNGs for legibility after layout changes.

## What PDF validation proves

The checker parses a complete multi-page PDF, verifies required sections/reviewer/repository/release entries, checks its canonical manifest digest, compares all URI annotations to exactly the expected manifest URLs and checks each page's status marker. XML-sensitive link text is escaped by the renderer. The page render step uses local PDFium only. Neither command visits a URL, logs in, sends invitations, uploads media nor contacts a student portal.

A successful **submission-mode structural check** only checks supplied metadata. It cannot independently verify a playable video, reviewer access or live deployment. The required reviewer remains `soat-architecture`; a pending invitation, public landing page or login screen is not authenticated access proof. External evidence must be supplied and manually reviewed before anyone changes the manifest to `submission` / `VERIFIED`.

## Remaining evidence before final rendering

1. Obtain the exact tested release revision and real deployment/evidence records; describe PASS, FAIL and NOT_RUN honestly. The separate staging control-plane note is not APP/FUN deployment proof.
2. Record the planned chapters with synthetic data; disclose prior recordings/time compression. Measure the finished video's actual duration (maximum 900 seconds), review audio/text readability and redact secrets/PII.
3. Obtain concrete authorization/destinations for publication and any reviewer invitation, then supply the four actual repository URLs, video URL and documentation URLs. Do not fabricate destinations to unblock the renderer.
4. Verify video playback and authenticated reviewer access to every repository, record durable redacted evidence links, and complete the manifest with that evidence. The source checks require four distinct repositories and a positive measured duration at most 900 seconds.
5. Render the final single PDF without preview flags; parse/render every page and manually exercise each actual exported link with the intended access context. Portal submission remains a separate authorized action after review of the finished file.

No upload, invitation, portal submission, live URL verification, measured recording or successful R4 release is claimed by this offline package.
