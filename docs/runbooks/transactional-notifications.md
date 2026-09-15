# Intenção de notificação transacional

A partir da V7, a abertura e cada transição válida da ordem gravam um evento `StatusOrdemServicoRegistrado` em `outbox_eventos`, na mesma transação do status, histórico e estoque. Uma falha de inserção desfaz toda a operação. O evento fica `PENDING` após o commit; a publicação externa pertence ao publicador A6 e a entrega ao consumidor F5. A5 isoladamente não entrega e-mails automaticamente.

Para conferir as intenções persistidas sem exibir contatos:

```sql
SELECT event_id, os_id, sequencia, estado, tentativas, disponivel_em
FROM outbox_eventos
ORDER BY criado_em DESC
LIMIT 20;
```

O payload segue o contrato imutável `contracts/phase3-v1/status-event.json`: apenas identificadores, versão da identidade, sequência, transição e referências de correlação. O limite é 8192 bytes; campos extras, versões desconhecidas e duplicação de `(os_id, sequencia, event_type)` são rejeitados. A origem gera uma nova correlação UUID e deixa `traceparent` nulo quando não existe contexto de rastreamento confiável. O registro não inclui observações livres, documentos, e-mail, telefone, JWT ou OTP.

`notificacao_destinatario_snapshot(ordem_id, numero, cliente_id, ativo, email, versao_identidade)` é uma visão do destinatário atual, incluindo CPF e CNPJ. O consumidor deve consultar por parâmetros vinculados e revalidar cliente, atividade e versão antes de resolver o contato. Mudanças de contato ou inativação não reescrevem eventos já persistidos. A configuração de roles/GRANT e sua verificação pertencem a I3/I6; esta migração não cria permissões amplas.

O adaptador `EmailNotificacaoAdapter` existe apenas no perfil Spring `local-mailhog`, para testes locais do consumidor com MailHog (`docker compose --profile tools up -d mailhog`). Seu método `enviar(evento, destinatario)` recusa execução dentro de uma transação. Ele não implementa `NotificacaoPort`; a única implementação dessa porta é o outbox. `MAIL_ENABLED` é legado e não desativa a persistência das intenções. `MAIL_HOST`, `MAIL_PORT` e `MAIL_FROM` continuam disponíveis para o destino local.

As recuperações operacionais ficam em `outbox_recuperacoes`, com ações `RETRY`/`SKIP`, referência simbólica do operador e código de motivo. Use códigos controlados; nunca grave contato, credenciais ou mensagens brutas de exceção nos campos de auditoria/erro. Os estados permitidos são `PENDING`, `PUBLISHED`, `BLOCKED` e `SKIPPED`; esta etapa não oferece endpoint de recuperação nem modifica estados para simular entrega.
