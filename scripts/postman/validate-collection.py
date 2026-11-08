#!/usr/bin/env python3
"""Valida offline a coleção Postman da Fase 4 contra os contratos OpenAPI versionados.

Verifica: (1) toda request de serviço corresponde a método+rota de um contrato (ou a uma exceção documentada);
(2) rotas protegidas usam auth bearer; (3) nenhum valor de segredo no arquivo da coleção ou dos environments;
(4) a ordem das pastas e dos passos da saga. Uso: python scripts/postman/validate-collection.py
"""
import json
import re
import sys
from pathlib import Path

import yaml

sys.stdout.reconfigure(encoding="utf-8")

RAIZ = Path(__file__).resolve().parents[2]
COLECAO = RAIZ / "postman" / "Oficina-Fase4-Kind.postman_collection.json"
ENVIRONMENTS = [RAIZ / "postman" / "kind-local.postman_environment.json",
                RAIZ / "postman" / "aws-academy.postman_environment.json"]
PREFIXO = {"baseUrlOs": "os", "baseUrlBilling": "billing", "baseUrlExecution": "execution"}
PUBLICAS = {("os", "GET", "/actuator/health"), ("billing", "GET", "/actuator/health/readiness"),
            ("execution", "GET", "/actuator/health")}
# Rotas sem contrato publicado, com justificativa verificável.
EXCECOES = {
    ("billing", "POST", "/simulacoes/pagamentos/{id}"): "controller com @Profile(\"gateway-fake\"); só existe no overlay kind-fake",
    ("billing", "GET", "/pagamentos"): "consulta por osId ainda inexistente no billing; a coleção tolera 404 (README seção 5)",
}
SEGREDOS_VARIAVEIS = {"adminSenha", "jwtSecret", "mpAccessToken"}  # devem ser type=secret e vazias
TOKENS_DE_FLUXO = {"token", "tokenServicos", "tokenCliente"}  # preenchidas só em runtime; vazias no arquivo
# Rotas públicas por desenho (sem bearer): login e a checagem de que rota protegida nega sem token.
SEM_AUTH_ESPERADO = {("os", "POST", "/auth/login"), ("os", "GET", "/clientes")}


def carregar_contratos():
    os_ = yaml.safe_load((RAIZ / "docs" / "openapi.yaml").read_text(encoding="utf-8"))
    cobranca = json.loads((RAIZ / "docs" / "phase4" / "api" / "billing-openapi.json").read_text(encoding="utf-8"))
    execucao = json.loads((RAIZ / "docs" / "phase4" / "api" / "execution-openapi.json").read_text(encoding="utf-8"))
    return {"os": os_, "billing": cobranca, "execution": execucao}


def rotas(contrato):
    return {(m.upper(), p) for p, ops in contrato["paths"].items() for m in ops
            if m.lower() in {"get", "post", "put", "delete", "patch"}}


def normalizar(caminho):
    return re.sub(r"\{\{[^}]+\}\}|\{[^}]+\}", "{id}", caminho)


def requests(itens, trilha=()):
    for item in itens:
        if "item" in item:
            yield from requests(item["item"], trilha + (item["name"],))
        else:
            yield trilha, item


def main():
    colecao = json.loads(COLECAO.read_text(encoding="utf-8"))
    contratos = carregar_contratos()
    conhecidas = {s: {(m, normalizar(p)) for m, p in rotas(c)} for s, c in contratos.items()}
    erros, vistas = [], set()
    for trilha, item in requests(colecao["item"]):
        req = item["request"]
        url = req["url"] if isinstance(req["url"], str) else req["url"]["raw"]
        m = re.match(r"\{\{(baseUrl\w+)\}\}(/[^?]*)", url)
        if not m or m.group(1) not in PREFIXO:
            continue  # URLs externas (Mercado Pago, LocalStack, emissor local) não são contratos dos três serviços
        servico, metodo, caminho = PREFIXO[m.group(1)], req["method"], normalizar(m.group(2))
        vistas.add((servico, metodo, caminho))
        nome = f"{'/'.join(trilha)}/{item['name']}"
        if (servico, metodo, caminho) in PUBLICAS:
            continue
        if (metodo, caminho) not in conhecidas[servico] and (servico, metodo, caminho) not in EXCECOES:
            erros.append(f"rota sem contrato: {servico} {metodo} {caminho} ({nome})")
        auth = (req.get("auth") or {}).get("type")
        if auth != "bearer" and (servico, metodo, caminho) not in SEM_AUTH_ESPERADO:
            erros.append(f"rota protegida sem auth bearer: {nome}")
    texto = COLECAO.read_text(encoding="utf-8")
    for arquivo in ENVIRONMENTS:
        for var in json.loads(arquivo.read_text(encoding="utf-8"))["values"]:
            if var["key"] in SEGREDOS_VARIAVEIS | TOKENS_DE_FLUXO and var.get("value"):
                erros.append(f"valor sensível preenchido em {arquivo.name}: {var['key']}")
            if var["key"] in SEGREDOS_VARIAVEIS and var.get("type") != "secret":
                erros.append(f"variável sensível sem type=secret em {arquivo.name}: {var['key']}")
    for var in colecao.get("variable", []):
        if var["key"] in SEGREDOS_VARIAVEIS | TOKENS_DE_FLUXO and var.get("value"):
            erros.append(f"valor de segredo na coleção: {var['key']}")
    if re.search(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.", texto) or re.search(r"(APP_USR|TEST)-[0-9a-f-]{20,}", texto):
        erros.append("possível token/credencial literal na coleção")
    pastas = [i["name"][:2] for i in colecao["item"]]
    if pastas != sorted(pastas):
        erros.append(f"pastas fora de ordem: {pastas}")
    passos = [i["name"] for _, i in requests(colecao["item"]) if i["name"].startswith("03.")]
    ordem = ["03.1", "03.2", "03.3", "03.4", "03.5", "03.6", "03.7", "03.8", "03.9", "03.10", "03.11", "03.12", "03.13", "03.14"]
    vistos = []
    for p in passos:
        n = p.split(" ")[0]
        if n not in vistos:
            vistos.append(n)
    if vistos != ordem:
        erros.append(f"ordem do fluxo feliz inesperada: {vistos}")
    cobertas = {s: {(m, c) for (sv, m, c) in vistas if sv == s} for s in contratos}
    print(f"requests de serviço validadas: {len(vistas)} rotas distintas; exceções documentadas: {len(EXCECOES)}")
    for chave, motivo in EXCECOES.items():
        print(f"  exceção {chave}: {motivo}")
    for s in ("billing", "execution"):
        print(f"cobertura {s}: {len(cobertas[s] & conhecidas[s])}/{len(conhecidas[s])} rotas do contrato")
    for e in erros:
        print("ERRO:", e)
    return 1 if erros else 0


if __name__ == "__main__":
    sys.exit(main())
