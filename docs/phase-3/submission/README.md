# Phase 3 offline submission preparation

Current status: **NOT_READY**. The [manifest template](submission-manifest.json) now records the four canonical repository URLs and reviewed source references: APP `8a40858e521edbb035afce653cfcc0142dee885a`, K8S `2c863ed6eaa0b3cae9bd05c84b21da0828b24e7c`, FUN `66e586ea43de1b98dc1347e49df75412b1f9faa2`, DB `33b7da9172c45d766d36e7561d59ee289053b02b`. Links pin current reviewed source or the original historical evidence revision. These are source references, not successful release/deployment claims or verified external-access evidence. Reviewer access remains false, access evidence/video URL remain `NOT_PROVIDED`, and measured duration remains `null`. The [14-minute script](video-script.md) is a recording plan, not a recording. The [eight R4 records](../evidence/manifest.json) remain separate deployment acceptance evidence; a PDF never changes them.

The optional schema-v1 `reviewedRevision` fields require all four immutable revisions and bind APP to `releaseRevision`; `pendingEvidence` explicitly lists R4/cloud activation, authenticated reviewer access, recording and final submission gaps. Template validation permits reviewed references while refusing access/video/readiness assertions. No AWS operation, live URL/access verification, recording or publication was performed for this refresh.

The [local acceptance checkpoint](../evidence/local-acceptance-2026-09-18.md) records ten suites with zero failures and `PASS_LOCAL_WITH_SKIPS`, with dynamic Helm checks explicitly skipped. APP PR #36 merged as `85a7227`; the receipt remains attributed to its tested head `9a4ba48`. The later [2026-09-19 staging attempt](../evidence/app-staging-attempt-2026-09-19.md), at APP `8e9cba5`, verified six published inputs but ended FAILED because EKS did not map the APP executor identity. Live RoleBinding verification, successful promotion and runtime acceptance remain pending. Submission remains NOT_READY.

## Local runtime and checks

Use the configured bundled Python runtime or a project-local virtual environment with the exact versions in [requirements-submission.txt](../../../scripts/submission/requirements-submission.txt): ReportLab 4.4.9 for PDF generation, pypdf 6.10.0 for parsing/content/link checks and pypdfium2 5.13.0 for rendering every page. No paid/cloud renderer is used. Do not install globally or silently accept a different runtime. A local alternative is `python -m venv .venv-submission`, then invoke that environment's Python with `-m pip install -r scripts/submission/requirements-submission.txt`.

From the APP root, run:

```text
python tests/documentation-provenance-contract.py --app-revision 8a40858e521edbb035afce653cfcc0142dee885a --k8s-revision 2c863ed6eaa0b3cae9bd05c84b21da0828b24e7c
python tests/submission-contract.py
python scripts/check-doc-links.py docs README.md
python scripts/submission/build_pdf.py --manifest docs/phase-3/submission/submission-manifest.json --allow-template --output artifacts/phase-3-submission.template.pdf
python scripts/submission/check_submission.py --manifest docs/phase-3/submission/submission-manifest.json --allow-template --output artifacts/phase-3-submission.template.pdf --render-pages artifacts/submission-template-pages
```

Without `--allow-template`, the committed template is rejected. Its generated PDF is watermarked `NOT_READY / FIXTURE_ONLY` on **every page**, renders known URLs as plain references with no external link annotations, and does not assert a completed release or reviewer access. The actual recorded duration stays `NOT_RECORDED`; the 14-minute target is explicitly labeled as a plan. Generated PDFs/page images stay under ignored `artifacts/`; regenerate them from the committed sources. Fixed PDF metadata makes the same template reproducible; metadata timestamps are not capture/deployment evidence.

The test-only `--allow-fixture` mode is separate. Its reserved `example.invalid` URLs, synthetic revision, duration and access assertions exist solely to exercise clickable-link rendering in a temporary directory. Never copy those values to the submission manifest. The tests prove that placeholders cannot pass submission mode, flags cannot validate incomplete real metadata, a `%PDF` header alone is insufficient, changed link targets are rejected and the PDF's manifest digest must match. They also render every template page; manually inspect the PNGs for legibility after layout changes.

## What PDF validation proves

The checker parses a complete multi-page PDF, verifies required sections/reviewer/repository/release entries, checks its canonical manifest digest, compares all URI annotations to exactly the expected manifest URLs and checks each page's status marker. XML-sensitive link text is escaped by the renderer. The page render step uses local PDFium only. Neither command visits a URL, logs in, sends invitations, uploads media nor contacts a student portal.

A successful **submission-mode structural check** only checks supplied metadata. It cannot independently verify a playable video, reviewer access or live deployment. The required reviewer remains `soat-architecture`; a pending invitation, public landing page or login screen is not authenticated access proof. External evidence must be supplied and manually reviewed before anyone changes the manifest to `submission` / `VERIFIED`.

## Remaining evidence before final rendering

1. Complete R4 cloud acceptance and APP cloud activation/promotion evidence against reviewed revisions; describe PASS, FAIL and NOT_RUN honestly. Existing source references and separate platform receipts do not prove APP runtime acceptance.
2. Record the planned chapters with synthetic data; disclose prior recordings/time compression. Measure the finished video's actual duration (maximum 900 seconds), review audio/text readability and redact secrets/PII.
3. Obtain concrete authorization/destinations for publication and any reviewer invitation, verify the supplied repository/documentation references, and supply the actual video URL. Do not fabricate destinations to unblock the renderer.
4. Verify video playback and authenticated reviewer access to every repository, record durable redacted evidence links, and complete the manifest with that evidence. The source checks require four distinct repositories and a positive measured duration at most 900 seconds.
5. Render the final single PDF without preview flags; parse/render every page and manually exercise each actual exported link with the intended access context. Portal submission remains a separate authorized action after review of the finished file.

No upload, invitation, portal submission, live URL verification, measured recording or successful R4 release is claimed by this offline package.
