#!/usr/bin/env node
// Resolvedor LOCAL de pagamentoId para a coleção Postman no Kind (Newman ou Runner).
// O billing não expõe GET /pagamentos?osId=; o id só existe no banco do billing. Este processo lê esse id (somente
// leitura) via `kubectl exec psql` e o devolve para a coleção. Escuta só em 127.0.0.1, responde só `{ "pagamentoId": "<uuid>" }`
// e nunca imprime nem devolve a senha do Postgres (lida do Secret em memória e enviada por stdin ao psql).
//
// Uso: KUBE_CONTEXT=kind-oficina-phase4 node scripts/postman/pagamento-id-resolver.mjs   (porta 8098)
// Environment da coleção: pagamentoResolverUrl=http://127.0.0.1:8098
// Escopo: Kind local. Não vale para o Learner Lab (RDS privado): lá o id sai do estado privado da sessão (ver docs/postman/README.md).
import { createServer } from 'node:http';
import { spawnSync } from 'node:child_process';

const PORT = Number(process.env.PORT ?? 8098);
const CONTEXT = process.env.KUBE_CONTEXT ?? 'kind-oficina-phase4';
const NAMESPACE = process.env.PG_NAMESPACE ?? 'data';
const DATABASE = process.env.PG_DATABASE ?? 'billing';
const ALLOWED_HOSTS = new Set([`127.0.0.1:${PORT}`, `localhost:${PORT}`]);
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const kubectl = (args, input) =>
  spawnSync('kubectl', ['--context', CONTEXT, ...args], { input, encoding: 'utf8', timeout: 20000 });

function senhaAdmin() {
  const r = kubectl(['get', 'secret', 'postgres-billing-admin', '-n', NAMESPACE, '-o', 'jsonpath={.data.password}']);
  if (r.status !== 0 || !r.stdout) throw new Error('secret postgres-billing-admin indisponível');
  return Buffer.from(r.stdout, 'base64').toString('utf8');
}

function pagamentoDaOs(osId) {
  const sql = `select id from pagamento where os_id = '${osId}' order by criado_em desc limit 1`; // osId validado como UUID
  const r = kubectl(['exec', '-i', '-n', NAMESPACE, 'deploy/postgres-billing', '--', 'sh', '-c',
    'read -r PGPASSWORD; export PGPASSWORD; exec psql "$@"', 'sh', '-X', '-q', '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', DATABASE],
  `${senhaAdmin()}\n${sql}\n`);
  if (r.status !== 0) throw new Error('consulta ao billing falhou');
  const id = r.stdout.trim();
  return UUID_RE.test(id) ? id : null;
}

function responder(res, status, corpo) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(corpo));
}

const server = createServer((req, res) => {
  if (!ALLOWED_HOSTS.has(req.headers.host ?? '') || req.headers.origin) return responder(res, 403, { erro: 'host/origin não permitido' });
  const url = new URL(req.url ?? '/', `http://${req.headers.host}`);
  if (req.method !== 'GET' || url.pathname !== '/pagamento-id') return responder(res, 404, { erro: 'GET /pagamento-id?osId=<uuid>' });
  const osId = url.searchParams.get('osId') ?? '';
  if (!UUID_RE.test(osId)) return responder(res, 400, { erro: 'osId deve ser UUID minúsculo' });
  try {
    const pagamentoId = pagamentoDaOs(osId);
    return pagamentoId ? responder(res, 200, { pagamentoId }) : responder(res, 404, { erro: 'pagamento ainda não existe' });
  } catch (e) {
    return responder(res, 502, { erro: e.message });
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Resolvedor de pagamentoId em http://127.0.0.1:${PORT}/pagamento-id?osId=<uuid> (contexto ${CONTEXT}; Ctrl+C encerra)`);
});
