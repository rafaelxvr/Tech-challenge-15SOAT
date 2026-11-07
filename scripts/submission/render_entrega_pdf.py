#!/usr/bin/env python3
"""Render a delivery document from Markdown to PDF using the project's print stylesheet.

The Phase 2 document was produced from Markdown with a CSS print stylesheet, so this
keeps the same source of truth and the same look: Markdown stays the thing people edit
and review, and the PDF is generated from it. Rendering goes through a headless
Chromium browser because it is the only engine on this machine that honours the
stylesheet's @page rules.

    python scripts/submission/render_entrega_pdf.py \\
        --source docs/entrega-fase-3.md \\
        --output docs/entrega-fase-3.pdf [--render-pages <dir>]

Links policy: only public http(s) links stay clickable. Links to the Paperclip tracker
and local relative links are printed as plain text (they would become dead file: URIs
in the PDF), and `#anchor` links stay internal. Local images are embedded as data URIs
and must live under the source directory. After printing, the PDF is audited with pypdf.
"""
import argparse
import base64
import mimetypes
import re
import shutil
import subprocess
import sys
import tempfile
from html import escape
from html.parser import HTMLParser
from pathlib import Path

import markdown
from markdown.extensions.toc import slugify_unicode
from pypdf import PdfReader

BROWSERS = [
    Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
]
EXTENSIONS = ["tables", "fenced_code", "attr_list", "md_in_html", "sane_lists"]
PUBLIC_LINK = re.compile(r"^https?://", re.IGNORECASE)
FORBIDDEN_TAGS = {"script", "iframe", "object", "embed", "link", "base", "source", "video", "audio", "form", "svg", "style", "meta"}
FORBIDDEN_PDF_URIS = ("file:", "/UNR/issues/")


class UnsafeAssetError(ValueError):
    """A local image that is absolute, missing or outside the allowed directory."""


class LinkAndImagePolicy(HTMLParser):
    """Enforce the link and image policy on the final HTML, raw HTML included.

    Python-Markdown restores raw HTML after its tree phase, so the policy runs on the
    finished document: public and internal links stay, other links become plain text,
    local images are embedded, and tags that can fetch or run anything are rejected.
    """

    def __init__(self, asset_root: Path):
        super().__init__(convert_charrefs=False)
        self.asset_root = asset_root.resolve()
        self.out = []
        self.anchors = []

    def apply(self, html: str) -> str:
        self.feed(html)
        self.close()
        return "".join(self.out)

    def handle_starttag(self, tag, attrs):
        self.out.append(self._start(tag, attrs, self_closing=False))

    def handle_startendtag(self, tag, attrs):
        self.out.append(self._start(tag, attrs, self_closing=True))

    def handle_endtag(self, tag):
        if tag == "a" and self.anchors:
            tag = self.anchors.pop()
        self.out.append(f"</{tag}>")

    def handle_data(self, data):
        self.out.append(data)

    def handle_entityref(self, name):
        self.out.append(f"&{name};")

    def handle_charref(self, name):
        self.out.append(f"&#{name};")

    def handle_comment(self, data):
        self.out.append(f"<!--{data}-->")

    def handle_decl(self, decl):
        self.out.append(f"<!{decl}>")

    def handle_pi(self, data):
        raise UnsafeAssetError("processing instructions are not allowed in the document")

    def _start(self, tag, attrs, self_closing):
        if tag in FORBIDDEN_TAGS:
            raise UnsafeAssetError(f"<{tag}> is not allowed in the document")
        attrs = [(name, value) for name, value in attrs if not name.lower().startswith("on")]
        if tag == "a":
            href = (dict(attrs).get("href") or "").strip()
            keep = href.startswith("#") or bool(PUBLIC_LINK.match(href))
            self.anchors.append("a" if keep else "span")
            if not keep:
                tag = "span"
                attrs = [(n, v) for n, v in attrs if n.lower() not in ("href", "title")]
        elif tag == "img":
            names = {name.lower() for name, _ in attrs}
            if names & {"srcset", "sizes"}:
                raise UnsafeAssetError("image srcset is not allowed")
            attrs = [(n, self._embed((v or "").strip()) if n.lower() == "src" else v) for n, v in attrs]
        rendered = "".join(
            f" {name}" if value is None else f' {name}="{escape(value, quote=True)}"' for name, value in attrs
        )
        return f"<{tag}{rendered}{' /' if self_closing else ''}>"

    def _embed(self, src: str) -> str:
        if src.startswith("data:"):
            return src
        if PUBLIC_LINK.match(src) or re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", src) or Path(src).is_absolute() or src.startswith(("/", "\\")):
            raise UnsafeAssetError(f"image must be a relative path under the document directory: {src}")
        target = (self.asset_root / src).resolve()
        if self.asset_root not in target.parents:
            raise UnsafeAssetError(f"image escapes the document directory: {src}")
        if not target.is_file():
            raise UnsafeAssetError(f"image not found: {src}")
        mime = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        return f"data:{mime};base64,{base64.b64encode(target.read_bytes()).decode('ascii')}"


def convert_markdown(body: str, asset_root: Path) -> str:
    """Markdown to HTML with a generated table of contents ([TOC]); the link policy runs on the final HTML."""
    engine = markdown.Markdown(
        extensions=[
            *EXTENSIONS,
            "toc",
        ],
        extension_configs={"toc": {"slugify": slugify_unicode, "toc_depth": "2-2"}},
    )
    return LinkAndImagePolicy(asset_root).apply(render_task_lists(engine.convert(body)))


def audit_pdf(pdf: Path) -> list:
    """Return the problems found in the printed PDF; an empty list means it is acceptable."""
    reader = PdfReader(str(pdf))
    problems, internal = [], 0
    for number, page in enumerate(reader.pages, start=1):
        for annotation in page.get("/Annots") or []:
            annotation = annotation.get_object()
            action = annotation.get("/A")
            uri = str(action.get_object().get("/URI", "")) if action else ""
            if any(bad in uri for bad in FORBIDDEN_PDF_URIS):
                problems.append(f"page {number}: forbidden link {uri[:80]}")
            if annotation.get("/Dest") is not None or (action and "/D" in action.get_object()):
                internal += 1
    if internal == 0:
        problems.append("no internal link destinations (table of contents is not navigable)")
    if not reader.outline:
        problems.append("no PDF bookmarks (outline)")
    return problems


def render_pages(pdf: Path, directory: Path) -> int:
    """Write one PNG per page for visual inspection."""
    import pypdfium2

    directory.mkdir(parents=True, exist_ok=True)
    document = pypdfium2.PdfDocument(str(pdf))
    for index in range(len(document)):
        document[index].render(scale=1.4).to_pil().save(directory / f"page-{index + 1:02d}.png")
    return len(document)


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
    parser.add_argument("--render-pages", type=Path, help="directory that receives one PNG per page")
    args = parser.parse_args()

    if not args.source.is_file():
        print(f"source not found: {args.source}", file=sys.stderr)
        return 1

    meta, body = split_front_matter(args.source.read_text(encoding="utf-8"))
    try:
        body_html = convert_markdown(body, args.source.resolve().parent)
    except UnsafeAssetError as error:
        print(f"unsafe asset: {error}", file=sys.stderr)
        return 1
    html = build_html(args.source, meta, body_html)

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
                "--generate-pdf-document-outline",
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
    problems = audit_pdf(args.output)
    if problems:
        print("PDF audit failed:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1
    if args.render_pages:
        print(f"{render_pages(args.output, args.render_pages)} page PNGs in {args.render_pages}")
    print(f"{args.output}  ({args.output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
