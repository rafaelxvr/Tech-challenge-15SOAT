package com.oficina.repository;

import com.oficina.config.ClockConfiguration;
import com.oficina.domain.identidade.DadosIdentidadeCliente;
import com.oficina.dto.ClienteRequest;
import com.oficina.entity.Cliente;
import com.oficina.entity.TipoDocumento;
import com.oficina.entity.Usuario;
import com.oficina.service.ClienteService;
import com.oficina.support.Fixtures;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ClienteService.class, ClienteIdentityAuditRepository.class, ClockConfiguration.class,
        ClienteIdentityPersistenceTest.FixedTime.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ClienteIdentityPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @Autowired ClienteRepository clientes;
    @Autowired ClienteService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagers;
    @Autowired PlatformTransactionManager transactions;
    private UUID clienteId;
    private UUID staffId;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM cliente_identidade_auditoria");
        jdbc.update("DELETE FROM clientes");
        staffId = jdbc.queryForObject("SELECT id FROM usuarios WHERE email = 'admin@oficina.com'", UUID.class);
        authenticate(staffId);
        clienteId = clientes.saveAndFlush(Fixtures.cliente(null)).getId();
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void persistedIdentityAndRowVersionsAdvanceSeparately() {
        Cliente created = clientes.findById(clienteId).orElseThrow();
        assertThat(created.getVersao()).isZero();
        assertThat(created.getVersaoIdentidade()).isEqualTo(1);

        service.atualizar(clienteId, request("changed@example.invalid"));
        Cliente changed = clientes.findById(clienteId).orElseThrow();
        assertThat(changed.getVersao()).isEqualTo(1);
        assertThat(changed.getVersaoIdentidade()).isEqualTo(2);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Cliente c = clientes.findById(clienteId).orElseThrow();
            c.setNome("Outro nome");
            c.setLogradouro("Outro endereço");
        });
        Cliente ordinaryEdit = clientes.findById(clienteId).orElseThrow();
        assertThat(ordinaryEdit.getVersao()).isEqualTo(2);
        assertThat(ordinaryEdit.getVersaoIdentidade()).isEqualTo(2);
    }

    @Test void staleCustomerUpdateFailsAndSurvivingVersionMatchesCommittedRecord() {
        var first = entityManagers.createEntityManager();
        var second = entityManagers.createEntityManager();
        try {
            first.getTransaction().begin();
            second.getTransaction().begin();
            Cliente winner = first.find(Cliente.class, clienteId);
            Cliente stale = second.find(Cliente.class, clienteId);
            winner.atualizarIdentidade(new DadosIdentidadeCliente(TipoDocumento.CPF, winner.getDocumento(), "winner@example.invalid", true));
            stale.atualizarIdentidade(new DadosIdentidadeCliente(TipoDocumento.CPF, stale.getDocumento(), "stale@example.invalid", true));
            first.getTransaction().commit();
            assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
            second.getTransaction().rollback();
            Cliente committed = clientes.findById(clienteId).orElseThrow();
            assertThat(committed.getEmail()).isEqualTo("winner@example.invalid");
            assertThat(committed.getVersaoIdentidade()).isEqualTo(winner.getVersaoIdentidade()).isEqualTo(2);
            assertThat(committed.getVersao()).isEqualTo(1);
        } finally {
            if (first.getTransaction().isActive()) first.getTransaction().rollback();
            if (second.getTransaction().isActive()) second.getTransaction().rollback();
            first.close();
            second.close();
        }
    }

    @Test void staleServiceTransactionRollsBackItsAuditAlongWithItsIdentityChange() {
        TransactionTemplate winner = new TransactionTemplate(transactions);
        winner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            clientes.findById(clienteId).orElseThrow(); // Keep the original version in this persistence context.
            winner.executeWithoutResult(inner -> service.atualizar(clienteId, request("winner@example.invalid")));
            service.atualizar(clienteId, request("stale@example.invalid"));
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Cliente committed = clientes.findById(clienteId).orElseThrow();
        assertThat(committed.getEmail()).isEqualTo("winner@example.invalid");
        assertThat(committed.getVersao()).isEqualTo(1);
        assertThat(committed.getVersaoIdentidade()).isEqualTo(2);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT versao_nova FROM cliente_identidade_auditoria WHERE cliente_id = ?",
                Long.class, clienteId)).isEqualTo(committed.getVersaoIdentidade());
    }

    @Test void viewHasOnlyFiveAllowedColumnsAndCpfCustomersIncludingInactive() {
        Cliente empresa = Cliente.builder().nome("Empresa").tipoDocumento(TipoDocumento.CNPJ)
                .documento("11.222.333/0001-81").email("empresa@example.invalid").telefone("11999999999").ativo(true).build();
        clientes.saveAndFlush(empresa);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'auth_cliente_snapshot' ORDER BY ordinal_position", String.class))
                .containsExactly("id", "cpf", "ativo", "email", "versao_identidade");
        assertThat(jdbc.queryForList("SELECT id FROM auth_cliente_snapshot", UUID.class)).containsExactly(clienteId);
        service.desativar(clienteId);
        var snapshot = jdbc.queryForMap("SELECT * FROM auth_cliente_snapshot WHERE id = ?", clienteId);
        assertThat(snapshot).containsEntry("cpf", "39053344705").containsEntry("ativo", false)
                .containsEntry("email", "cliente@example.invalid").containsEntry("versao_identidade", 2L);
    }

    @Test void auditContainsOnlyFieldNamesActorTimeAndVersionTransition() {
        service.atualizar(clienteId, request("changed@example.invalid"));
        jdbc.query("SELECT * FROM cliente_identidade_auditoria WHERE cliente_id = ?", rs -> {
            assertThat(rs.getObject("staff_id", UUID.class)).isEqualTo(staffId);
            assertThat((String[]) rs.getArray("campos").getArray()).containsExactly("email");
            assertThat(rs.getLong("versao_anterior")).isEqualTo(1);
            assertThat(rs.getLong("versao_nova")).isEqualTo(2);
            assertThat(rs.getTimestamp("ocorrido_em").toInstant()).isEqualTo(NOW);
        }, clienteId);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'cliente_identidade_auditoria' ORDER BY ordinal_position", String.class))
                .containsExactly("id", "cliente_id", "staff_id", "campos", "versao_anterior", "versao_nova", "ocorrido_em");
    }

    @Test void unchangedIdentityCreatesNoAuditAndRepeatedDeactivationIsIdempotent() {
        service.atualizar(clienteId, request("cliente@example.invalid"));
        assertThat(auditCount()).isZero();
        service.desativar(clienteId);
        service.desativar(clienteId);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(clientes.findById(clienteId).orElseThrow().getVersaoIdentidade()).isEqualTo(2);
    }

    @Test void auditInsertFailureRollsBackIdentityAndOrdinaryEdits() {
        authenticate(UUID.randomUUID()); // A real FK failure in the audit INSERT.
        assertThatThrownBy(() -> service.atualizar(clienteId, request("changed@example.invalid")))
                .isInstanceOf(DataIntegrityViolationException.class);
        Cliente unchanged = clientes.findById(clienteId).orElseThrow();
        assertThat(unchanged.getEmail()).isEqualTo("cliente@example.invalid");
        assertThat(unchanged.getNome()).isEqualTo("Cliente de teste");
        assertThat(unchanged.getVersaoIdentidade()).isEqualTo(1);
        assertThat(unchanged.getVersao()).isZero();
        assertThat(auditCount()).isZero();
    }

    @Test void databaseRejectsNonPositiveIdentityVersion() {
        assertThatThrownBy(() -> jdbc.update("UPDATE clientes SET versao_identidade = 0 WHERE id = ?", clienteId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM cliente_identidade_auditoria", Integer.class);
    }

    private static ClienteRequest request(String email) {
        return new ClienteRequest("Nome atualizado", TipoDocumento.CPF, "390.533.447-05", email,
                "11999999999", null, null, null, null, null, null, null);
    }

    private static void authenticate(UUID id) {
        Usuario staff = Usuario.builder().id(id).role(Usuario.Role.ADMIN).ativo(true).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(staff, null, List.of()));
    }
}
