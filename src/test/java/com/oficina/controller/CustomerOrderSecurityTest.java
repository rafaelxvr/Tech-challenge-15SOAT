package com.oficina.controller;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.entity.*;
import com.oficina.repository.*;
import com.oficina.service.OrdemServicoService;
import com.oficina.support.TokenFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Full application security chain and signed fixtures; /api is the servlet context. */
@SpringBootTest(properties = {"oficina.mail.enabled=false", "management.health.mail.enabled=false",
        "oficina.historico.zona-compatibilidade=UTC"})
@AutoConfigureMockMvc
@Import(CustomerOrderSecurityTest.TestClock.class)
class CustomerOrderSecurityTest {
    static final TokenFixtures TOKENS = new TokenFixtures();
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static { POSTGRES.start(); }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        var jwt = TOKENS.properties();
        properties.add("security.jwt.secret", jwt::secret);
        properties.add("security.jwt.staff.issuer", jwt.staff()::issuer);
        properties.add("security.jwt.staff.audience", jwt.staff()::audience);
        properties.add("security.jwt.staff.key-id", jwt.staff()::keyId);
        properties.add("security.jwt.customer.issuer", jwt.customer()::issuer);
        properties.add("security.jwt.customer.audience", jwt.customer()::audience);
        properties.add("security.jwt.customer.public-keys." + TokenFixtures.CUSTOMER_KID,
                () -> jwt.customer().publicKeys().get(TokenFixtures.CUSTOMER_KID));
    }

    @TestConfiguration static class TestClock {
        @Bean @Primary Clock fixtureClock() { return TokenFixtures.CLOCK; }
    }

    @Autowired MockMvc mvc;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @SpyBean OrdemServicoRepository ordens;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    @Autowired PecaRepository pecas;
    @Autowired ServicoRepository servicos;
    @Autowired UsuarioRepository usuarios;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean OrdemServicoService service;
    @MockBean NotificacaoPort notifications;
    Cliente clienteA;
    Cliente clienteB;
    OrdemServico ordemPersistida;

    @BeforeEach void persistRealOrder() {
        usuarios.findByEmail(TokenFixtures.STAFF_EMAIL).orElseGet(() -> usuarios.save(Usuario.builder()
                .email(TokenFixtures.STAFF_EMAIL).senha("fixture-only-unused").ativo(true).role(Usuario.Role.MECANICO).build()));
        clienteA = clientes.findByDocumento("52998224725").orElseGet(() -> clientes.save(customer("52998224725")));
        clienteB = clientes.findByDocumento("39053344705").orElseGet(() -> clientes.save(customer("39053344705")));
        ordemPersistida = new TransactionTemplate(transactions).execute(status -> {
            Veiculo vehicle = veiculos.save(Veiculo.builder().cliente(clienteB)
                    .placa(UUID.randomUUID().toString().substring(0, 8)).marca("Fiat").modelo("Uno")
                    .ano(2020).ativo(true).build());
            Peca part = pecas.save(Peca.builder().codigo(UUID.randomUUID().toString()).nome("Filtro")
                    .quantidadeEstoque(10).quantidadeMinima(1).valorUnitario(BigDecimal.TEN)
                    .unidadeMedida("UN").ativo(true).build());
            OrdemServico order = com.oficina.support.Fixtures.ordem(clienteB, StatusOrdemServico.AGUARDANDO_APROVACAO);
            order.setVeiculo(vehicle);
            Servico work = servicos.save(Servico.builder().nome("Troca de filtro").valor(new BigDecimal("50.00"))
                    .tempoEstimadoMin(30).ativo(true).build());
            order.adicionarServico(OsServicoItem.builder().servico(work).quantidade(1)
                    .valorUnitario(new BigDecimal("50.00")).valorTotal(new BigDecimal("50.00"))
                    .observacao("private@example.invalid").build());
            order.getHistorico().get(0).setObservacao("Internal contact private@example.invalid");
            order.adicionarPeca(OsPecaItem.builder().peca(part).quantidade(2)
                    .valorUnitario(BigDecimal.TEN).valorTotal(new BigDecimal("20.00")).build());
            order.recalcularValorTotal();
            return ordens.saveAndFlush(order);
        });
        assertThat(ordemPersistida.getNumero()).isPositive();
        clearInvocations(service, notifications);
    }

    private Cliente customer(String document) {
        return Cliente.builder().nome("Nome privado").tipoDocumento(TipoDocumento.CPF).documento(document)
                .email("private@example.invalid").telefone("11999999999").ativo(true).build();
    }

    private String token(Cliente customer) {
        return "Bearer " + TOKENS.customer(customer.getId(), 1, "staging", TokenFixtures.NOW.plusSeconds(900));
    }

    @Test void anotherCustomersOrderIsHiddenAndOwnerCanRead() throws Exception {
        assertThat(ordemPersistida.getCliente().getId()).isEqualTo(clienteB.getId());
        mvc.perform(get("/api/ordens-servico/{numero}/acompanhamento", ordemPersistida.getNumero())
                        .contextPath("/api").header("Authorization", token(clienteA)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ordens-servico/{numero}/acompanhamento", ordemPersistida.getNumero())
                        .contextPath("/api").header("Authorization", token(clienteB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.numero").value(ordemPersistida.getNumero()));
    }

    @Test void retiredEmailTokenCannotReachAnyServiceMethod() throws Exception {
        mvc.perform(post("/api/ordens-servico/email/atualizar-status").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numero\":" + ordemPersistida.getNumero()
                                + ",\"novoStatus\":\"EM_EXECUCAO\",\"token\":\"oficina-email-status-token\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service, notifications);
    }

    @Test void retiredRouteIsUnavailableWithCustomerStaffOrMalformedBearer() throws Exception {
        for (String bearer : List.of(token(clienteB), "Bearer " + TOKENS.staff("access", "staging"),
                "Bearer oficina-email-status-token")) {
            mvc.perform(post("/api/ordens-servico/email/atualizar-status").contextPath("/api")
                            .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"oficina-email-status-token\"}"))
                    .andExpect(status().isNotFound());
        }
        verifyNoInteractions(service, notifications);
        assertStockAndHistory("AGUARDANDO_APROVACAO", 10, 0);
    }

    private String decisionPath(String suffix) {
        return "/api/ordens-servico/" + ordemPersistida.getNumero() + suffix;
    }

    private String decisionBody(String suffix, String decision, String document) {
        return "{\"decisao\":\"" + decision + "\",\"documentoCliente\":\"" + document
                + "\",\"observacao\":\"Meu parecer privado\"}";
    }

    @ParameterizedTest @ValueSource(strings = {"/orcamento/decisao", "/aprovar", "/orcamento/notificacao"})
    void canonicalAndAliasesHideWrongOwnerAndCommitExactlyOneApproval(String suffix) throws Exception {
        // Even the real owner's legacy document must not select that identity for another customer's token.
        String body = decisionBody(suffix, "APROVADO", clienteB.getDocumento());
        mvc.perform(post(decisionPath(suffix)).contextPath("/api").header("Authorization", token(clienteA))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        assertStockAndHistory("AGUARDANDO_APROVACAO", 10, 0);
        mvc.perform(post(decisionPath(suffix)).contextPath("/api").header("Authorization", token(clienteB))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EM_EXECUCAO"))
                .andExpect(jsonPath("$.data.cliente").doesNotExist());
        // Canonical and aliases all reach the same aggregate state guard.
        mvc.perform(post(decisionPath("/orcamento/decisao")).contextPath("/api").header("Authorization", token(clienteB))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decisao\":\"APROVADO\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertStockAndHistory("EM_EXECUCAO", 8, 1);
        assertThat(jdbc.queryForObject("SELECT ator_cliente_id FROM os_historico WHERE os_id=? AND sequencia=4",
                UUID.class, ordemPersistida.getId())).isEqualTo(clienteB.getId());
        assertThat(jdbc.queryForObject("SELECT alterado_por FROM os_historico WHERE os_id=? AND sequencia=4",
                UUID.class, ordemPersistida.getId())).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"/orcamento/decisao", "/orcamento/notificacao"})
    void canonicalAndLegacyRefusalUseCustomerActorWithoutStockMovement(String suffix) throws Exception {
        mvc.perform(post(decisionPath(suffix)).contextPath("/api").header("Authorization", token(clienteB))
                        .contentType(MediaType.APPLICATION_JSON).content(decisionBody(suffix, "RECUSADO", clienteB.getDocumento())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EM_DIAGNOSTICO"));
        assertStockAndHistory("EM_DIAGNOSTICO", 10, 0);
        assertThat(jdbc.queryForObject("SELECT ator_tipo FROM os_historico WHERE os_id=? AND sequencia=4",
                String.class, ordemPersistida.getId())).isEqualTo("CUSTOMER");
    }

    @ParameterizedTest @ValueSource(strings = {"/aprovar", "/orcamento/notificacao"})
    void legacyDocumentMustAgreeWithTheAuthenticatedOwner(String suffix) throws Exception {
        mvc.perform(post(decisionPath(suffix)).contextPath("/api").header("Authorization", token(clienteB))
                        .contentType(MediaType.APPLICATION_JSON).content(decisionBody(suffix, "APROVADO", clienteA.getDocumento())))
                .andExpect(status().isUnprocessableEntity());
        assertStockAndHistory("AGUARDANDO_APROVACAO", 10, 0);
    }

    @Test void projectionContainsEstimateButNoIdentityContactOrInternalNotes() throws Exception {
        var result = mvc.perform(get(decisionPath("/acompanhamento")).contextPath("/api")
                        .header("Authorization", token(clienteB))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pecas[0].descricao").value("Filtro"))
                .andExpect(jsonPath("$.data.pecas[0].quantidade").value(2))
                .andExpect(jsonPath("$.data.servicos[0].descricao").value("Troca de filtro"))
                .andExpect(jsonPath("$.data.servicos[0].valorTotal").value(50))
                .andExpect(jsonPath("$.data.valorTotal").value(70)).andReturn();
        var fields = new ArrayList<String>();
        var data = mapper.readTree(result.getResponse().getContentAsString()).path("data");
        data.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("numero", "status", "valorTotal", "criadoEm",
                "ultimaAtualizacaoStatusEm", "servicos", "pecas", "historico");
        String json = data.toString();
        assertThat(json).doesNotContain("private@example.invalid", "Nome privado", clienteB.getDocumento(),
                clienteB.getId().toString(), "observacao", "ator", "alteradoPor", "placa", "clienteNome");
    }

    private String onlyScope(String scope) {
        var claims = TOKENS.claims("customerAccess", "staging");
        claims.put("sub", clienteB.getId().toString());
        claims.put("scopes", List.of(scope));
        return "Bearer " + TOKENS.signCustomer(claims, Map.of("kid", TokenFixtures.CUSTOMER_KID));
    }

    @Test void exactReadAndDecisionScopesAreIndependent() throws Exception {
        mvc.perform(get(decisionPath("/acompanhamento")).contextPath("/api")
                        .header("Authorization", onlyScope("orders:decide:self"))).andExpect(status().isForbidden());
        mvc.perform(post(decisionPath("/orcamento/decisao")).contextPath("/api")
                        .header("Authorization", onlyScope("orders:read:self"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decisao\":\"APROVADO\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(decisionPath("/acompanhamento")).contextPath("/api")
                        .header("Authorization", onlyScope("orders:read:self"))).andExpect(status().isOk());
        mvc.perform(post(decisionPath("/orcamento/decisao")).contextPath("/api")
                        .header("Authorization", onlyScope("orders:decide:self"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decisao\":\"APROVADO\"}"))
                .andExpect(status().isOk());
    }

    @Test void customerCannotUseStaffRoutesAndStaffCannotUseCustomerRoutes() throws Exception {
        mvc.perform(get("/api/ordens-servico").contextPath("/api").header("Authorization", token(clienteB)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ordens-servico").contextPath("/api").header("Authorization", token(clienteB))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ordens-servico/{id}/finalizar", ordemPersistida.getId()).contextPath("/api")
                        .header("Authorization", token(clienteB))).andExpect(status().isForbidden());
        String staff = "Bearer " + TOKENS.staff("access", "staging");
        mvc.perform(get(decisionPath("/acompanhamento")).contextPath("/api").header("Authorization", staff))
                .andExpect(status().isForbidden());
        mvc.perform(post(decisionPath("/orcamento/decisao")).contextPath("/api").header("Authorization", staff)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decisao\":\"APROVADO\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ordens-servico").contextPath("/api").header("Authorization", staff))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ordens-servico").contextPath("/api").header("Authorization", staff)
                        .param("status", "AGUARDANDO_APROVACAO").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("AGUARDANDO_APROVACAO"));
        mvc.perform(get("/api/admin/metricas").contextPath("/api").header("Authorization", staff))
                .andExpect(status().isForbidden());
    }

    @Test void onlyApprovedLoginAndMinimalHealthRemainAnonymous() throws Exception {
        for (String path : List.of(decisionPath("/acompanhamento"), "/api/actuator/info", "/api/v3/api-docs",
                "/api/swagger-ui.html", "/api/auth/unknown")) {
            mvc.perform(get(path).contextPath("/api")).andExpect(status().isUnauthorized());
        }
        for (String suffix : List.of("/aprovar", "/orcamento/notificacao", "/orcamento/decisao")) {
            mvc.perform(post(decisionPath(suffix)).contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decisao\":\"APROVADO\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/actuator/health").contextPath("/api")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(post("/api/auth/login").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void simultaneousAuthorizedApprovalsCommitOneHistoryAndOneStockDeduction() throws Exception {
        var rendezvous = new CyclicBarrier(2);
        doAnswer(call -> { rendezvous.await(15, TimeUnit.SECONDS); entityManager.flush(); return null; })
                .when(ordens).flush();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> approve = () -> mvc.perform(post(decisionPath("/orcamento/decisao"))
                            .contextPath("/api").header("Authorization", token(clienteB))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"decisao\":\"APROVADO\"}"))
                    .andReturn().getResponse().getStatus();
            var first = pool.submit(approve);
            var second = pool.submit(approve);
            assertThat(List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertStockAndHistory("EM_EXECUCAO", 8, 1);
        } finally { pool.shutdownNow(); }
    }

    private void assertStockAndHistory(String status, int stock, int approvals) {
        assertThat(jdbc.queryForObject("SELECT status::text FROM ordens_servico WHERE id=?", String.class,
                ordemPersistida.getId())).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT p.quantidade_estoque FROM pecas p JOIN os_pecas op ON op.peca_id=p.id WHERE op.os_id=?",
                Integer.class, ordemPersistida.getId())).isEqualTo(stock);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM os_historico WHERE os_id=? AND status_novo='EM_EXECUCAO'",
                Integer.class, ordemPersistida.getId())).isEqualTo(approvals);
    }
}
