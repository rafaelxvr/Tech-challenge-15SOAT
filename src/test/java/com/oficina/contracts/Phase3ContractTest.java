package com.oficina.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class Phase3ContractTest {

    private static final Path CONTRACTS = Path.of("contracts/phase3-v1");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void eventContainsExactReferencesWithoutContactOrSecurityData() throws Exception {
        Path fixture = CONTRACTS.resolve("status-event.json");
        JsonNode event = MAPPER.readTree(fixture.toFile());

        assertThat(fieldNames(event)).containsExactlyInAnyOrder(
                "eventId", "eventType", "schemaVersion", "ordemId", "numero", "clienteId",
                "versaoIdentidadeCliente", "sequencia", "statusAnterior", "statusNovo",
                "ocorridoEm", "correlationId", "traceparent");
        assertThat(event.path("eventId").textValue()).isEqualTo("00000000-0000-0000-0000-000000000101");
        assertThat(event.path("eventType").textValue()).isEqualTo("StatusOrdemServicoRegistrado");
        assertThat(event.path("schemaVersion").isIntegralNumber()).isTrue();
        assertThat(event.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(event.path("ordemId").textValue()).isEqualTo("00000000-0000-0000-0000-000000000201");
        assertThat(event.path("numero").longValue()).isEqualTo(1001L);
        assertThat(event.path("clienteId").textValue()).isEqualTo("00000000-0000-0000-0000-000000000301");
        assertThat(event.path("versaoIdentidadeCliente").longValue()).isEqualTo(1L);
        assertThat(event.path("sequencia").longValue()).isEqualTo(1L);
        assertThat(event.get("statusAnterior").isNull()).isTrue();
        assertThat(event.path("statusNovo").textValue()).isEqualTo("RECEBIDA");
        assertThat(event.path("ocorridoEm").textValue()).isEqualTo("2026-09-15T12:00:00Z");
        assertThat(event.path("correlationId").textValue()).isEqualTo("00000000-0000-0000-0000-000000000401");
        assertThat(event.get("traceparent").isNull()).isTrue();
        assertThat(lowerCaseFieldNamesRecursively(event)).doesNotContain(
                "cpf", "email", "jwt", "token", "authorization", "contato", "telefone");
        assertThat(Files.size(fixture)).isLessThanOrEqualTo(8192L);
    }

    @Test
    void tokenClaimsPinEnvironmentTrustPurposeAndPermissions() throws Exception {
        JsonNode root = MAPPER.readTree(CONTRACTS.resolve("token-claims.json").toFile());
        assertThat(root.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(root.path("algorithms").path("customer").textValue()).isEqualTo("RS256");
        assertThat(root.path("algorithms").path("staff").textValue()).isEqualTo("HS256");

        assertEnvironment(root.path("environments").path("staging"), "staging");
        assertEnvironment(root.path("environments").path("production"), "production");
    }

    @Test
    void routeMatrixIsExplicitCompleteAndDefaultDeny() throws Exception {
        JsonNode root = MAPPER.readTree(CONTRACTS.resolve("routes.json").toFile());
        assertThat(root.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(root.path("defaultDecision").textValue()).isEqualTo("DENY");
        assertThat(root.path("routes").isArray()).isTrue();
        assertThat(root.path("routes")).hasSize(37);

        Set<String> actualRouteKeys = new HashSet<>();
        for (JsonNode route : root.path("routes")) {
            String key = route.path("method").textValue() + " " + route.path("path").textValue();
            assertThat(actualRouteKeys.add(key)).as("duplicate route %s", key).isTrue();
            assertThat(route.path("path").textValue()).doesNotContain("**", "/*");
            assertThat(route.has("grants")).isTrue();
        }
        assertThat(actualRouteKeys).containsExactlyInAnyOrderElementsOf(expectedRouteKeys());

        assertGrant(root, "POST /api/auth/cpf/desafios", "anonymous", Set.of(), Set.of());
        assertGrant(root, "POST /api/auth/cpf/verificar", "anonymous", Set.of(), Set.of());
        assertGrant(root, "POST /api/auth/login", "anonymous", Set.of(), Set.of());
        assertGrant(root, "GET /health", "anonymous", Set.of(), Set.of());
        assertGrant(root, "GET /api/ordens-servico/{numero}/acompanhamento", "customer", Set.of(), Set.of("orders:read:self"));
        assertGrant(root, "POST /api/ordens-servico/{numero}/orcamento/decisao", "customer", Set.of(), Set.of("orders:decide:self"));
        assertGrant(root, "POST /api/ordens-servico/{numero}/orcamento/notificacao", "customer", Set.of(), Set.of("orders:decide:self"));
        assertGrant(root, "POST /api/ordens-servico/{numero}/aprovar", "customer", Set.of(), Set.of("orders:decide:self"));

        JsonNode retired = findRoute(root, "POST /api/ordens-servico/email/atualizar-status");
        assertThat(retired.path("decision").textValue()).isEqualTo("DENY");
        assertThat(retired.path("grants")).isEmpty();
    }

    private static void assertEnvironment(JsonNode environment, String name) {
        String audience = "oficina-" + name + "-api";
        JsonNode customer = environment.path("customerAccess");
        assertThat(customer.path("iss").textValue()).isEqualTo("oficina-" + name + "-customer");
        assertThat(customer.path("aud").textValue()).isEqualTo(audience);
        assertThat(customer.path("principal_type").textValue()).isEqualTo("customer");
        assertThat(customer.path("token_use").textValue()).isEqualTo("access");
        assertThat(customer.path("sub").textValue()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(customer.path("identity_version").longValue()).isPositive();
        assertThat(customer.path("exp").longValue() - customer.path("iat").longValue()).isEqualTo(900L);
        assertThat(strings(customer.path("scopes"))).containsExactlyInAnyOrder(
                "orders:read:self", "orders:decide:self");

        JsonNode staffAccess = environment.path("staffAccess");
        JsonNode staffRefresh = environment.path("staffRefresh");
        assertThat(staffAccess.path("iss").textValue()).isEqualTo("oficina-" + name + "-staff");
        assertThat(staffAccess.path("aud").textValue()).isEqualTo(audience);
        assertThat(staffAccess.path("principal_type").textValue()).isEqualTo("staff");
        assertThat(staffAccess.path("token_use").textValue()).isEqualTo("access");
        assertThat(staffAccess.path("exp").longValue()).isGreaterThan(staffAccess.path("iat").longValue());
        assertThat(staffAccess.path("sub").textValue()).isEqualTo(staffRefresh.path("sub").textValue());
        assertThat(staffAccess.path("sub").textValue()).contains("@");
        assertThat(staffRefresh.path("iss").textValue()).isEqualTo("oficina-" + name + "-staff");
        assertThat(staffRefresh.path("aud").textValue()).isEqualTo(audience);
        assertThat(staffRefresh.path("principal_type").textValue()).isEqualTo("staff");
        assertThat(staffRefresh.path("token_use").textValue()).isEqualTo("refresh");
        assertThat(staffRefresh.path("exp").longValue()).isGreaterThan(staffRefresh.path("iat").longValue());
    }

    private static void assertGrant(JsonNode root, String routeKey, String actor,
                                    Set<String> roles, Set<String> scopes) {
        JsonNode route = findRoute(root, routeKey);
        assertThat(route.path("decision").textValue()).isEqualTo("ALLOW");
        assertThat(StreamSupport.stream(route.path("grants").spliterator(), false)
                .anyMatch(grant -> actor.equals(grant.path("actor").textValue())
                        && strings(grant.path("roles")).equals(roles)
                        && strings(grant.path("scopes")).equals(scopes)))
                .as("grant for %s", routeKey).isTrue();
    }

    private static JsonNode findRoute(JsonNode root, String routeKey) {
        return StreamSupport.stream(root.path("routes").spliterator(), false)
                .filter(route -> routeKey.equals(route.path("method").textValue() + " " + route.path("path").textValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing route " + routeKey));
    }

    private static Set<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::textValue)
                .collect(Collectors.toSet());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> lowerCaseFieldNamesRecursively(JsonNode node) {
        Set<String> names = new HashSet<>();
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                names.add(field.getKey().toLowerCase());
                names.addAll(lowerCaseFieldNamesRecursively(field.getValue()));
            }
        } else if (node.isArray()) {
            node.forEach(child -> names.addAll(lowerCaseFieldNamesRecursively(child)));
        }
        return names;
    }

    private static Set<String> expectedRouteKeys() {
        return Set.of(
                "POST /api/auth/cpf/desafios", "POST /api/auth/cpf/verificar", "POST /api/auth/login", "GET /health",
                "GET /api/clientes", "GET /api/clientes/{id}", "POST /api/clientes", "PUT /api/clientes/{id}", "DELETE /api/clientes/{id}",
                "GET /api/veiculos", "GET /api/veiculos/{id}", "POST /api/veiculos", "PUT /api/veiculos/{id}", "DELETE /api/veiculos/{id}",
                "GET /api/servicos", "GET /api/servicos/{id}", "POST /api/servicos", "PUT /api/servicos/{id}", "DELETE /api/servicos/{id}",
                "GET /api/pecas", "GET /api/pecas/{id}", "POST /api/pecas", "PUT /api/pecas/{id}", "DELETE /api/pecas/{id}",
                "POST /api/ordens-servico", "GET /api/ordens-servico", "GET /api/ordens-servico/{id}",
                "GET /api/ordens-servico/{numero}/acompanhamento", "POST /api/ordens-servico/{numero}/orcamento/decisao",
                "POST /api/ordens-servico/{numero}/orcamento/notificacao", "POST /api/ordens-servico/{numero}/aprovar",
                "POST /api/ordens-servico/email/atualizar-status", "POST /api/ordens-servico/{id}/iniciar-diagnostico",
                "POST /api/ordens-servico/{id}/enviar-orcamento", "POST /api/ordens-servico/{id}/finalizar",
                "POST /api/ordens-servico/{id}/entregar", "GET /api/admin/metricas/tempo-execucao-servicos");
    }
}
