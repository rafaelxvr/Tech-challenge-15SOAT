#!/usr/bin/env python3
"""Render a delivery document from Markdown to PDF using the project's print stylesheet.

The Phase 2 document was produced from Markdown with a CSS print stylesheet, so this
keeps the same source of truth and the same look: Markdown stays the thing people edit
and review, and the PDF is generated from it. Rendering goes through a headless
Chromium browser because it is the only engine on this machine that honours the
stylesheet's @page rules.

    python scripts/submission/render_entrega_pdf.py \\
        --source docs/entrega-fase-3.md \\
        --output docs/entrega-fase-3.pdf
"""
import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import markdown

BROWSERS = [
    Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
]
EXTENSIONS = ["tables", "fenced_code", "attr_list", "md_in_html", "sane_lists"]


def split_front_matter(text):
    """Return (metadata, body). The front matter carries the title and the stylesheet."""
    if not text.startswith("---"):
        return {}, text
    end = text.find("\n---", 3)
    if end == -1:
        return {}, text
    meta = {}
    for line in text[3:end].splitlines():
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        meta[key.strip()] = value.strip().strip('"')
    return meta, text[end + 4:]


def render_task_lists(html):
    """Markdown leaves '[x]' as literal text; show it as a checked or empty box."""
    html = html.replace("<li>[x] ", '<li class="task done">')
    html = html.replace("<li>[ ] ", '<li class="task">')
    return html


def build_html(source: Path, meta, body_html: str) -> str:
    css_name = meta.get("css", "assets/pdf-style.css")
    css_path = (source.parent / css_name).resolve()
    css = css_path.read_text(encoding="utf-8") if css_path.is_file() else ""
    title = meta.get("title", source.stem)
    lang = meta.get("lang", "pt-BR")
    return f"""<!DOCTYPE html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<title>{title}</title>
<style>
{css}
.page-break {{ page-break-after: always; }}
li.task {{ list-style: none; margin-left: -1.1em; }}
li.task::before {{ content: "\\2610\\00a0"; }}
li.task.done::before {{ content: "\\2611\\00a0"; }}
</style>
</head>
<body>
{body_html}
</body>
</html>
"""


def find_browser() -> Path:
    for candidate in BROWSERS:
        if candidate.is_file():
            return candidate
    raise FileNotFoundError("no Chromium-based browser found to print the PDF")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    if not args.source.is_file():
        print(f"source not found: {args.source}", file=sys.stderr)
        return 1

    meta, body = split_front_matter(args.source.read_text(encoding="utf-8"))
    html = build_html(args.source, meta, render_task_lists(markdown.markdown(body, extensions=EXTENSIONS)))

    workdir = Path(tempfile.mkdtemp(prefix="entrega-pdf-"))
    try:
        page = workdir / "document.html"
        page.write_text(html, encoding="utf-8")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            [
                str(find_browser()),
                "--headless=new",
                "--disable-gpu",
                "--no-pdf-header-footer",
                f"--print-to-pdf={args.output.resolve()}",
                page.resolve().as_uri(),
            ],
            check=True,
            capture_output=True,
            timeout=180,
        )
    except subprocess.CalledProcessError as error:
        print(f"browser refused to print: {error.stderr.decode(errors='replace')[:400]}", file=sys.stderr)
        return 1
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    if not args.output.is_file() or args.output.stat().st_size == 0:
        print("the browser produced no PDF", file=sys.stderr)
        return 1
    print(f"{args.output}  ({args.output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
