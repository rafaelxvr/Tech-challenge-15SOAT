package com.oficina.saga;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oficina.dto.*;
import com.oficina.entity.Cliente;
import com.oficina.repository.ClienteRepository;
import com.oficina.saga.application.CicloOsSagaService;
import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.security.IdentidadeAutenticada;
import com.oficina.security.TipoPrincipal;
import com.oficina.service.OrdemServicoService;
import com.oficina.support.SagaFixtures;
import com.oficina.support.TokenFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"oficina.mail.enabled=false", "management.health.mail.enabled=false",
        "oficina.historico.zona-compatibilidade=UTC", "oficina.saga.enabled=true",
        "oficina.saga.mensageria.enabled=true"})
class SagaMensageriaLocalStackTest {
    static final TokenFixtures TOKENS = new TokenFixtures();
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.SQS);
    static String billing, execucao, respostas;

    static {
        POSTGRES.start();
        LOCALSTACK.start();
        try (SqsClient sqs = sqs()) {
            billing = criarFila(sqs, "billing-commands.fifo");
            execucao = criarFila(sqs, "execution-commands.fifo");
            respostas = criarFila(sqs, "os-saga-replies.fifo");
        }
    }

    static SqsClient sqs() {
        return SqsClient.builder().endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }

    static String criarFila(SqsClient sqs, String nome) {
        return sqs.createQueue(CreateQueueRequest.builder().queueName(nome)
                .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")).build()).queueUrl();
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
        var jwt = TOKENS.properties();
        p.add("security.jwt.secret", jwt::secret);
        p.add("security.jwt.staff.issuer", jwt.staff()::issuer);
        p.add("security.jwt.staff.audience", jwt.staff()::audience);
        p.add("security.jwt.staff.key-id", jwt.staff()::keyId);
        p.add("security.jwt.customer.issuer", jwt.customer()::issuer);
        p.add("security.jwt.customer.audience", jwt.customer()::audience);
        p.add("security.jwt.customer.public-keys." + TokenFixtures.CUSTOMER_KID,
                () -> jwt.customer().publicKeys().get(TokenFixtures.CUSTOMER_KID));
        p.add("oficina.saga.mensageria.region", LOCALSTACK::getRegion);
        p.add("oficina.saga.mensageria.endpoint",
                () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS).toString());
        p.add("oficina.saga.mensageria.fila-billing", () -> billing);
        p.add("oficina.saga.mensageria.fila-execucao", () -> execucao);
        p.add("oficina.saga.mensageria.fila-respostas", () -> respostas);
    }

    @Autowired OrdemServicoService service;
    @Autowired CicloOsSagaService ciclo;
    @Autowired MapeadorMensagens mapeador;
    @Autowired ClienteRepository clientes;
    @Autowired JdbcTemplate jdbc;
    final SqsClient sqs = sqs();
    Cliente cliente;

    @BeforeEach void dados() { cliente = SagaFixtures.cliente(clientes); }

    OrdemServicoDetalheResponse abrir(int estoque) {
        UUID servico = SagaFixtures.servico(jdbc, "80.00");
        UUID peca = SagaFixtures.peca(jdbc, estoque, "35.50");
        UUID veiculo = SagaFixtures.veiculo(jdbc, cliente.getId());
        String placa = jdbc.queryForObject("SELECT placa FROM veiculos WHERE id=?", String.class, veiculo);
        return service.criar(new CriarOrdemServicoRequest(cliente.getDocumento(), placa, null, null, null,
                List.of(new ItemServicoOsRequest(servico, 1, null)), List.of(new ItemPecaOsRequest(peca, 2)), null));
    }

    Envelope aguardarComando(String fila, String tipo, UUID osId) {
        AtomicReference<Envelope> achado = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            for (Message m : sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(fila)
                    .maxNumberOfMessages(10).waitTimeSeconds(1).build()).messages()) {
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(fila).receiptHandle(m.receiptHandle()).build());
                Envelope e = mapeador.ler(m.body());
                if (e.type().equals(tipo) && e.osId().equals(osId)) achado.set(e);
            }
            return achado.get() != null;
        });
        return achado.get();
    }

    void responder(Envelope comando, String tipo, ObjectNode payload) {
        Envelope resposta = new Envelope(UUID.randomUUID(), Envelope.Kind.REPLY, tipo, 1, comando.sagaId(),
                comando.osId(), comando.passo(), comando.messageId(), Instant.now(), comando.correlationId(), null, payload);
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(respostas).messageBody(mapeador.escrever(resposta))
                .messageGroupId(comando.osId().toString()).messageDeduplicationId(resposta.messageId().toString()).build());
    }

    String estadoSaga(UUID osId) { return ciclo.resumo(osId).orElseThrow().estado(); }

    void aguardarEstado(UUID osId, String estado) {
        await().atMost(Duration.ofSeconds(30)).until(() -> estadoSaga(osId).equals(estado));
    }

    void decidir(long numero) {
        service.decidirComoCliente(numero, new IdentidadeAutenticada(TipoPrincipal.CUSTOMER, cliente.getId(),
                Set.of("SCOPE_orders:decide:self"), 1),
                new DecisaoClienteRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO, null));
    }

    @Test void fluxoCompletoAteFinalizada() {
        var os = abrir(5);
        var diag = aguardarComando(execucao, "IniciarDiagnostico", os.id());
        responder(diag, "DiagnosticoConcluido", JsonNodeFactory.instance.objectNode());
        var gerar = aguardarComando(billing, "GerarOrcamento", os.id());
        assertThat(gerar.payload().get("itens")).hasSize(2);
        responder(gerar, "OrcamentoGerado", JsonNodeFactory.instance.objectNode()
                .put("orcamentoId", UUID.randomUUID().toString()).put("total", 151.00));
        aguardarEstado(os.id(), "AGUARDANDO_DECISAO");
        decidir(os.numero());
        var decisao = aguardarComando(billing, "RegistrarDecisao", os.id());
        responder(decisao, "OrcamentoAprovado", JsonNodeFactory.instance.objectNode()
                .put("checkoutUrl", "https://sandbox.mercadopago.example/checkout/1"));
        aguardarEstado(os.id(), "AGUARDANDO_PAGAMENTO");
        responder(decisao, "PagamentoConfirmado", JsonNodeFactory.instance.objectNode());
        var reparo = aguardarComando(execucao, "IniciarReparo", os.id());
        responder(reparo, "ReparoIniciado", JsonNodeFactory.instance.objectNode());
        aguardarEstado(os.id(), "EM_EXECUCAO");
        responder(reparo, "ExecucaoFinalizada", JsonNodeFactory.instance.objectNode());
        aguardarEstado(os.id(), "CONCLUIDA");
        assertThat(jdbc.queryForObject("SELECT status::text FROM ordens_servico WHERE id=?", String.class, os.id()))
                .isEqualTo("FINALIZADA");
    }

    @Test void semEstoqueEstornaCancelaOrcamentoECancelaOs() {
        var os = abrir(1);
        var diag = aguardarComando(execucao, "IniciarDiagnostico", os.id());
        responder(diag, "DiagnosticoConcluido", JsonNodeFactory.instance.objectNode());
        var gerar = aguardarComando(billing, "GerarOrcamento", os.id());
        responder(gerar, "OrcamentoGerado", JsonNodeFactory.instance.objectNode()
                .put("orcamentoId", UUID.randomUUID().toString()).put("total", 151.00));
        aguardarEstado(os.id(), "AGUARDANDO_DECISAO");
        decidir(os.numero());
        var decisao = aguardarComando(billing, "RegistrarDecisao", os.id());
        responder(decisao, "OrcamentoAprovado", JsonNodeFactory.instance.objectNode()
                .put("checkoutUrl", "https://sandbox.mercadopago.example/checkout/2"));
        responder(decisao, "PagamentoConfirmado", JsonNodeFactory.instance.objectNode());
        var estorno = aguardarComando(billing, "EstornarPagamento", os.id());
        assertThat(estorno.payload().get("motivo").asText()).isEqualTo("ESTOQUE_INSUFICIENTE");
        responder(estorno, "PagamentoEstornado", JsonNodeFactory.instance.objectNode());
        var cancelar = aguardarComando(billing, "CancelarOrcamento", os.id());
        responder(cancelar, "OrcamentoCancelado", JsonNodeFactory.instance.objectNode());
        aguardarEstado(os.id(), "CANCELADA");
        assertThat(jdbc.queryForObject("SELECT status::text FROM ordens_servico WHERE id=?", String.class, os.id()))
                .isEqualTo("CANCELADA");
    }
}
