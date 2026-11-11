#!/usr/bin/env python3
"""Confere que todo path citado em docs/roteiro-video-fase-4.md existe no repositório, na tag/branch do link.

Para cada link https://github.com/rafaelxvr/<repo>/(blob|tree)/<ref>/<path>:
  - core (Tech-challenge-15SOAT): `git ls-tree` no checkout local (ref local ou origin/<ref>);
  - billing e execution: API do GitHub via `gh api` (árvore recursiva do ref).
Também confere que cada caminho local `D:\\repository\\fiap-entrega\\<path>` bate com um link de blob do core.

Uso: python scripts/check-roteiro-paths.py [roteiro.md]   (sai com 1 se algo não existir)
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROTEIRO = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "docs" / "roteiro-video-fase-4.md"
OWNER = "rafaelxvr"
CORE = "Tech-challenge-15SOAT"
LINK = re.compile(r"https://github\.com/%s/(Tech-challenge-15SOAT(?:-billing|-execution)?)/(blob|tree)/([^/\s)]+)(?:/([^\s)>`]+))?" % OWNER)
LOCAL = re.compile(r"D:\\repository\\fiap-entrega\\([^\s`<]+)")

_cache = {}


def run(cmd, cwd=None):
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", cwd=cwd)
    return r.returncode, r.stdout


def tree_paths(repo, ref):
    key = (repo, ref)
    if key in _cache:
        return _cache[key]
    paths = None
    if repo == CORE:
        for candidate in ("origin/" + ref, ref):
            code, out = run(["git", "ls-tree", "-r", "--name-only", candidate], cwd=ROOT)
            if code == 0:
                paths = set(out.splitlines())
                break
    else:
        code, out = run(["gh", "api", f"repos/{OWNER}/{repo}/git/trees/{ref}?recursive=1"])
        if code == 0:
            paths = {item["path"] for item in json.loads(out)["tree"]}
    _cache[key] = paths
    return paths


def check(text):
    errors, checked, core_blobs = [], 0, set()
    for m in LINK.finditer(text):
        repo, kind, ref, path = m.group(1), m.group(2), m.group(3), m.group(4)
        paths = tree_paths(repo, ref)
        if paths is None:
            errors.append(f"ref inexistente: {repo}@{ref}")
            continue
        if path is None:
            checked += 1
            continue
        path = path.rstrip(".,;:")
        if kind == "blob":
            ok = path in paths
        else:
            ok = any(p == path or p.startswith(path + "/") for p in paths)
        checked += 1
        if not ok:
            errors.append(f"path inexistente: {repo}@{ref}:{path}")
        elif repo == CORE and kind == "blob":
            core_blobs.add(path)
    for m in LOCAL.finditer(text):
        path = m.group(1).rstrip(".,;:").replace("\\", "/")
        checked += 1
        if path not in core_blobs:
            errors.append(f"caminho local sem link de blob do core correspondente: {path}")
    return errors, checked


def self_test():
    errors, _ = check("[x](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/__nao-existe__.md)")
    assert errors, "o checker deveria reprovar path inexistente"
    print("self-test OK")


def main():
    if "--self-test" in sys.argv:
        sys.argv.remove("--self-test")
        self_test()
        return 0
    errors, checked = check(ROTEIRO.read_text(encoding="utf-8"))
    for e in errors:
        print("ERRO:", e)
    print(f"{checked} referências conferidas, {len(errors)} erro(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
