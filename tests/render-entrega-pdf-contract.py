"""Contract for scripts/submission/render_entrega_pdf.py; offline, never contacts URLs."""
import importlib.util
import subprocess
import sys
import tempfile
from pathlib import Path

from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/submission/render_entrega_pdf.py"
SOURCE = ROOT / "docs/entrega-fase-4.md"
spec = importlib.util.spec_from_file_location("render_entrega_pdf", SCRIPT)
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)
checks = 0


def check(condition, message):
    global checks
    if not condition:
        raise AssertionError(message)
    checks += 1


with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    (root / "img.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"/>', encoding="utf-8")
    convert = lambda text: renderer.convert_markdown(text, root)

    html = convert("[tarefa](/UNR/issues/UNR-1) e [local](x.md) e [web](https://example.org/a)")
    check("/UNR/issues" not in html and 'href="x.md"' not in html, "paperclip/relative links must be flattened")
    check("tarefa" in html and "local" in html, "flattened links keep their text")
    check('href="https://example.org/a"' in html, "https links stay clickable")
    check('href="#sec"' in convert("[ir](#sec)"), "anchors stay internal")
    check("data:image/svg+xml;base64," in convert("![d](img.svg)"), "local image must be embedded")
    for bad in ("../outside.svg", "/etc/passwd", "C:/Windows/x.png", "file:///x.png", "missing.svg"):
        try:
            convert(f"![x]({bad})")
        except renderer.UnsafeAssetError:
            checks += 1
        else:
            raise AssertionError(f"unsafe image accepted: {bad}")

    raw = convert(
        '<a href="file:///C:/Users/Rafael/secret.txt">local</a>\n'
        '<a href="/UNR/issues/UNR-1">paperclip</a>\n'
        '<a href="  JavaScript:alert(1)">js</a>\n'
        '<a href="https://example.org/ok">web</a>\n'
        '<a href="#sec">ancora</a>'
    )
    check("file:" not in raw and "/UNR/issues" not in raw and "javascript:" not in raw.lower(), "raw html links must be flattened")
    check("<a href=" in raw and raw.count("<a ") == 2 and raw.count("</a>") == 2, "raw html keeps only https and anchors")
    check(">local</span>" in raw and ">paperclip</span>" in raw, "raw html links keep their text")
    for bad in (
        '<img src="file:///C:/Users/Rafael/secret.png">',
        '<img src="https://example.org/remote.png">',
        '<img src="/abs.png">',
        '<img src="../outside.svg">',
        '<img src="img.svg" srcset="https://example.org/x.png 2x">',
        '<script src="https://example.org/x.js"></script>',
        '<iframe src="https://example.org"></iframe>',
        '<div style="background-image:url(https://example.org/leak.png)">x</div>',
        '<p style="background:url(https://example.org/leak.png)">x</p>',
        '<input type="image" src="https://example.org/leak.png">',
        '<table background="https://example.org/leak.png"><tr><td>x</td></tr></table>',
        '<video poster="https://example.org/leak.png"></video>',
        '<p data="https://example.org/leak.png">x</p>',
        '<p action="https://example.org/leak">x</p>',
        '<p formaction="https://example.org/leak">x</p>',
        '<p xlink:href="https://example.org/leak.png">x</p>',
        '<p srcset="https://example.org/leak.png 2x">x</p>',
        '<td BACKGROUND="https://example.org/leak.png">x</td>',
        '<p src="https://example.org/leak.png">x</p>',
        '<div style="BACKGROUND : UrL (https://example.org/l.png)">x</div>',
        '<div style="background:u/**/rl(https://example.org/l.png)">x</div>',
        r'<div style="background:\75rl(https://example.org/l.png)">x</div>',
        '<div style="background:url&#40;https://example.org/l.png)">x</div>',
        "<div style=\"@import 'https://example.org/a.css'\">x</div>",
        '<div style="background:image-set(https://example.org/l.png 1x)">x</div>',
        '<div style="background:-webkit-image-set(https://example.org/l.png 1x)">x</div>',
        '<div style="width:expression(alert(1))">x</div>',
    ):
        try:
            convert(bad)
        except renderer.UnsafeAssetError:
            checks += 1
        else:
            raise AssertionError(f"unsafe raw html accepted: {bad}")
    check("data:image/svg+xml;base64," in convert('<img src="img.svg" alt="x">'), "raw html local image is embedded")

    benign = convert('<div style="color: red; text-align:center" onclick="x()" data-x="1" foo="bar">ok</div>')
    check('style="color: red; text-align:center"' in benign, "harmless inline style is kept")
    check("onclick" not in benign and "foo=" not in benign and "data-x" not in benign, "unlisted attributes are dropped")

    page = renderer.build_html(SOURCE, {}, "<p>x</p>")
    check(
        '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src data:; '
        "style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">" in page,
        "built html carries the exact CSP",
    )

    toc = convert("[TOC]\n\n## 1. Seção única\n\ntexto")
    check('href="#1-seção-única"' in toc and 'id="1-seção-única"' in toc, "toc links must match heading ids")

    pdf = root / "out.pdf"
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--source", str(SOURCE), "--output", str(pdf)],
        capture_output=True, text=True, timeout=300,
    )
    check(result.returncode == 0, result.stdout + result.stderr)
    reader = PdfReader(str(pdf))
    text = "\n".join(page.extract_text() for page in reader.pages)
    raw = pdf.read_bytes()
    check(b"file:" not in raw and b"/UNR/issues/" not in raw, "pdf must not carry file: or Paperclip URIs")
    check(len(reader.outline) > 0, "pdf must expose bookmarks")
    check("NÃO ENVIAR" in text and "RASCUNHO" in text, "draft banner must remain")
    check("PENDENTE-VIDEO" in text and "[CONFIRMAR]" in text, "evidence placeholders must remain")
    check("C:" + "\\Users" not in text, "no local paths in text")

css = (ROOT / "docs/assets/pdf-style.css").read_text(encoding="utf-8")
import re
sizes = {m.group(1): float(m.group(2)) for m in re.finditer(r"(html,\s*body|table|\.small)\s*\{[^}]*?font-size:\s*([\d.]+)pt", css)}
check(sizes.get("table", 0) >= 8.5 and sizes.get(".small", 0) >= 8.5, "table/.small >= 8.5pt")
check(any(v >= 10 for k, v in sizes.items() if k.startswith("html")), "body >= 10pt")
print(f"PASS: {checks} checks")
