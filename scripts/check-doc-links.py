#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path
app_root = Path(__file__).resolve().parents[1]
workspace_root = app_root.parents[2]
self_test = "--self-test" in sys.argv
arguments = [argument for argument in sys.argv[1:] if argument != "--self-test"]
required = [
 "README.md",
 "docs/phase-3/README.md", "docs/phase-3/architecture/components.md",
 "docs/phase-3/architecture/authentication-sequence.md", "docs/phase-3/architecture/order-opening-sequence.md",
 "docs/phase-3/architecture/data-model.md", "docs/phase-3/api/contracts.md",
 "docs/phase-3/api/openapi-7ca6e294.yaml", "docs/phase-3/api/postman-7ca6e294.json",
 "docs/phase-3/api/openapi-7ca6e294.sha256", "docs/phase-3/api/postman-7ca6e294.sha256",
 *[f"docs/rfcs/{n:03d}-{name}.md" for n,name in enumerate(["aws-profile","postgresql-model","cpf-authentication","notifications","observability"],1)],
 *[f"docs/adrs/{n:03d}-{name}.md" for n,name in enumerate(["modular-monolith","environment-scaling","token-trust","outbox-delivery","canonical-reporting"],1)],
]
if self_test:
    required.append("docs/phase-3/__checker-missing-artifact__.md")
errors=[]
for item in required:
    if not Path(item).is_file(): errors.append(f"missing required: {item}")
external_required = [
    workspace_root / "oficina-k8s-infra/README.md", workspace_root / "oficina-k8s-infra/docs/architecture.md",
    workspace_root / "oficina-functions/README.md", workspace_root / "oficina-functions/docs/architecture.md",
    workspace_root / "oficina-db-infra/README.md", workspace_root / "oficina-db-infra/docs/architecture.md",
]
for path in external_required:
    if not path.is_file(): errors.append(f"missing cross-repository artifact: {path}")
    else:
        content = path.read_text(encoding="utf-8")
        for required_text in ["api/contracts.md", "Technologies", "Prerequisites"]:
            if required_text not in content:
                errors.append(f"incomplete repository matrix ({required_text}): {path}")
        if "```mermaid" not in content:
            errors.append(f"missing repository architecture diagram: {path}")

for artifact in [Path("docs/phase-3/api/postman-7ca6e294.json")]:
    try:
        payload = json.loads(artifact.read_text(encoding="utf-8"))
        if not payload.get("info"): errors.append(f"invalid API snapshot: {artifact}")
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"invalid JSON {artifact}: {exc}")

sources = [*map(Path, arguments or ["docs", "README.md"]), *external_required]
for root in sources:
    paths = root.rglob("*.md") if root.is_dir() else [root]
    for path in paths:
        if not path.is_file(): continue
        content = path.read_text(encoding="utf-8")
        for target in re.findall(r"\[[^]]*\]\(([^)#]+)", content):
            if "://" in target or target.startswith("#"): continue
            resolved = (path.parent / target).resolve()
            # Docs use the portable sibling-checkout APP path. This worktree maps it
            # back to its checkout solely for local validation.
            marker = "Tech-challenge-15SOAT"
            parts = Path(target).parts
            if not resolved.exists() and marker in parts:
                resolved = app_root.joinpath(*parts[parts.index(marker) + 1:])
            if not resolved.exists():
                sibling = next((part for part in parts if part in {"oficina-k8s-infra", "oficina-functions", "oficina-db-infra"}), None)
                if sibling:
                    resolved = workspace_root / sibling / Path(*parts[parts.index(sibling) + 1:])
            if not resolved.exists(): errors.append(f"broken: {path} -> {target}")
        blocks = re.findall(r"```mermaid\s*\n(.*?)```", content, flags=re.DOTALL)
        if "```mermaid" in content and not blocks: errors.append(f"unclosed Mermaid block: {path}")
        for block in blocks:
            if not re.match(r"\s*(flowchart|sequenceDiagram|erDiagram|stateDiagram-v2|C4Context|C4Container|C4Component|C4Deployment|architecture-beta)\b", block):
                errors.append(f"unsupported Mermaid declaration: {path}")
for path in [Path("docs/phase-3/architecture/components.md"), Path("docs/phase-3/architecture/authentication-sequence.md"), Path("docs/phase-3/architecture/order-opening-sequence.md"), Path("docs/phase-3/architecture/data-model.md")]:
    if not re.search(r"```mermaid\s*\n", path.read_text(encoding="utf-8")):
        errors.append(f"missing required Mermaid diagram: {path}")
baseline_errors = [error for error in errors if "__checker-missing-artifact__" not in error]
if self_test and baseline_errors:
    print("\n".join(baseline_errors))
    print("self-test failed: baseline validation has unrelated errors")
    sys.exit(1)
if errors:
    print("\n".join(errors))
    if self_test and any("__checker-missing-artifact__" in error for error in errors):
        print("PASS: checker rejects a missing required artifact.")
        sys.exit(0)
    sys.exit(1)
if self_test:
    print("self-test failed: synthetic missing artifact was accepted")
    sys.exit(1)
print("PASS: required documentation matrix, local links, JSON snapshots, and Mermaid declarations are present.")
