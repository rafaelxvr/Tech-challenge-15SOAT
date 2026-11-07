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
