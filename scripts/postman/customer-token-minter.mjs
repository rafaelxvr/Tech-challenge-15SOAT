#!/usr/bin/env node
// Emissor LOCAL de token de cliente (RS256) para testes no Kind via Postman/Newman.
// Em produção o token vem do Lambda de CPF/OTP (oficina-functions), que não existe no Kind.
// O par RSA é efêmero (memória): nada é gravado em disco. A chave pública é impressa no
// stdout para ser configurada no core; o processo escuta somente em 127.0.0.1.
//
// Uso: node scripts/postman/customer-token-minter.mjs
// Env: PORT (8099) KID (kind-local) CUSTOMER_ISSUER (oficina-kind-customer)
//      CUSTOMER_AUDIENCE (oficina-kind-api)
import { createServer } from 'node:http';
import { generateKeyPairSync, createSign, randomUUID } from 'node:crypto';

const PORT = Number(process.env.PORT ?? 8099);
const KID = process.env.KID ?? 'kind-local';
const ISSUER = process.env.CUSTOMER_ISSUER ?? 'oficina-kind-customer';
const AUDIENCE = process.env.CUSTOMER_AUDIENCE ?? 'oficina-kind-api';
const TTL_SECONDS = 900;
const SCOPES = ['orders:read:self', 'orders:decide:self'];
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const b64url = (value) => Buffer.from(value).toString('base64url');

function mint(sub, identityVersion) {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', typ: 'JWT', kid: KID };
  const claims = {
    iss: ISSUER, aud: AUDIENCE, sub, principal_type: 'customer', token_use: 'access',
    identity_version: identityVersion, iat: now, exp: now + TTL_SECONDS, scopes: SCOPES, jti: randomUUID(),
  };
  const unsigned = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const signature = createSign('RSA-SHA256').update(unsigned).sign(privateKey);
  return `${unsigned}.${signature.toString('base64url')}`;
}

function reply(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

const server = createServer((req, res) => {
  if (req.method !== 'POST' || req.url !== '/token') return reply(res, 404, { error: 'NOT_FOUND' });
  let raw = '';
  req.on('data', (chunk) => { raw += chunk; if (raw.length > 4096) req.destroy(); });
  req.on('end', () => {
    try {
      const { sub, identityVersion = 1 } = JSON.parse(raw);
      if (!UUID_RE.test(sub) || !Number.isInteger(identityVersion) || identityVersion < 1) {
        return reply(res, 400, { error: 'sub deve ser UUID e identityVersion inteiro >= 1' });
      }
      return reply(res, 200, { data: { accessToken: mint(sub, identityVersion) } });
    } catch {
      return reply(res, 400, { error: 'JSON inválido' });
    }
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Emissor de token de cliente em http://127.0.0.1:${PORT}/token (Ctrl+C encerra)`);
  console.log(`Configure no core: JWT_CUSTOMER_ISSUER=${ISSUER} JWT_CUSTOMER_AUDIENCE=${AUDIENCE}`);
  console.log(`e security.jwt.customer.public-keys.${KID} com a chave pública abaixo:\n`);
  console.log(publicKey.export({ type: 'spki', format: 'pem' }));
});
