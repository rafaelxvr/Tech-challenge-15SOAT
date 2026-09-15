package com.oficina.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.controller.AuthController;
import com.oficina.controller.ClienteController;
import com.oficina.controller.MetricasAdminController;
import com.oficina.controller.OrdemServicoController;
import com.oficina.controller.PecaController;
import com.oficina.controller.ServicoCatalogoController;
import com.oficina.controller.VeiculoController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class Phase3ContractTest {

    private static final Path CONTRACTS = Path.of("contracts/phase3-v1");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Class<?>> CONTROLLERS = List.of(
            AuthController.class,
            ClienteController.class,
            MetricasAdminController.class,
            OrdemServicoController.class,
            PecaController.class,
            ServicoCatalogoController.class,
            VeiculoController.class);

    private static final Map<String, String> PLANNED_BINDINGS = Map.of(
            "POST /api/auth/cpf/desafios", "CriarDesafioHandler",
            "POST /api/auth/cpf/verificar", "VerificarDesafioHandler",
            "GET /health", "ReadinessHealth",
            "POST /api/ordens-servico/{numero}/orcamento/decisao", "OrdemServicoController.decisaoOrcamento");

    private static final Set<String> PUBLIC_ROUTES = Set.of(
            "POST /api/auth/cpf/desafios",
            "POST /api/auth/cpf/verificar",
            "POST /api/auth/login",
            "GET /health");

    private static final Set<String> CUSTOMER_DECISION_ROUTES = Set.of(
            "POST /api/ordens-servico/{numero}/orcamento/decisao",
            "POST /api/ordens-servico/{numero}/orcamento/notificacao",
            "POST /api/ordens-servico/{numero}/aprovar");

    private static final String CUSTOMER_TRACKING_ROUTE =
            "GET /api/ordens-servico/{numero}/acompanhamento";
    private static final String RETIRED_EMAIL_ROUTE =
            "POST /api/ordens-servico/email/atualizar-status";

    private static final Set<String> ADMIN_ONLY_ROUTES = Set.of(
            "POST /api/clientes",
            "PUT /api/clientes/{id}",
            "DELETE /api/clientes/{id}",
            "DELETE /api/veiculos/{id}",
            "POST /api/servicos",
            "PUT /api/servicos/{id}",
            "DELETE /api/servicos/{id}",
            "POST /api/pecas",
            "PUT /api/pecas/{id}",
            "DELETE /api/pecas/{id}",
            "GET /api/admin/metricas/tempo-execucao-servicos");

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
    void routeMatrixMatchesControllerMappingsAndEveryApprovedPolicy() throws Exception {
        JsonNode root = MAPPER.readTree(CONTRACTS.resolve("routes.json").toFile());
        assertThat(root.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(root.path("defaultDecision").textValue()).isEqualTo("DENY");
        assertThat(root.path("routes").isArray()).isTrue();

        Map<String, String> controllerBindings = controllerBindings();
        assertThat(controllerBindings.keySet()).doesNotContainAnyElementsOf(PLANNED_BINDINGS.keySet());
        Map<String, String> expectedBindings = new LinkedHashMap<>(controllerBindings);
        expectedBindings.putAll(PLANNED_BINDINGS);

        Map<String, JsonNode> routesByKey = new LinkedHashMap<>();
        for (JsonNode route : root.path("routes")) {
            String key = route.path("method").textValue() + " " + route.path("path").textValue();
            assertThat(routesByKey.put(key, route)).as("duplicate route %s", key).isNull();
            assertThat(route.path("path").textValue()).doesNotContain("**", "/*");
        }
        assertThat(routesByKey.keySet()).containsExactlyInAnyOrderElementsOf(expectedBindings.keySet());

        expectedBindings.forEach((routeKey, operation) -> assertRoute(
                routesByKey.get(routeKey),
                routeKey,
                expectedOwner(routeKey),
                operation,
                expectedDecision(routeKey),
                expectedGrants(routeKey)));
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

    private static Map<String, String> controllerBindings() {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (Class<?> controller : CONTROLLERS) {
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            assertThat(classMapping).as("class mapping for %s", controller.getSimpleName()).isNotNull();
            String basePath = singlePath(classMapping);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) {
                    continue;
                }
                assertThat(methodMapping.method()).as("HTTP method for %s.%s", controller.getSimpleName(), method.getName())
                        .hasSize(1);
                String routeKey = methodMapping.method()[0].name() + " /api" + basePath + singlePath(methodMapping);
                String operation = controller.getSimpleName() + "." + method.getName();
                assertThat(bindings.put(routeKey, operation)).as("duplicate controller mapping %s", routeKey).isNull();
            }
        }
        return bindings;
    }

    private static String singlePath(RequestMapping mapping) {
        String[] paths = mapping.path();
        assertThat(paths).hasSizeLessThanOrEqualTo(1);
        return paths.length == 0 ? "" : paths[0];
    }

    private static void assertRoute(JsonNode route, String routeKey, String owner, String operation,
                                    String decision, Set<Grant> grants) {
        assertThat(route).as("fixture route %s", routeKey).isNotNull();
        assertThat(route.path("owner").textValue()).as("owner for %s", routeKey).isEqualTo(owner);
        assertThat(route.path("operation").textValue()).as("operation for %s", routeKey).isEqualTo(operation);
        assertThat(route.path("decision").textValue()).as("decision for %s", routeKey).isEqualTo(decision);
        assertThat(route.path("grants").isArray()).as("grants for %s", routeKey).isTrue();
        Set<Grant> actualGrants = StreamSupport.stream(route.path("grants").spliterator(), false)
                .map(grant -> parseGrant(grant, routeKey))
                .collect(Collectors.toSet());
        assertThat(route.path("grants")).as("grant count for %s", routeKey).hasSize(actualGrants.size());
        assertThat(actualGrants).as("complete grants for %s", routeKey).containsExactlyInAnyOrderElementsOf(grants);
    }

    private static Grant parseGrant(JsonNode grant, String routeKey) {
        assertThat(grant.path("actor").isTextual()).as("grant actor for %s", routeKey).isTrue();
        assertThat(grant.path("roles").isArray()).as("grant roles for %s", routeKey).isTrue();
        assertThat(grant.path("scopes").isArray()).as("grant scopes for %s", routeKey).isTrue();
        Set<String> roles = strings(grant.path("roles"));
        Set<String> scopes = strings(grant.path("scopes"));
        assertThat(grant.path("roles")).as("unique grant roles for %s", routeKey).hasSize(roles.size());
        assertThat(grant.path("scopes")).as("unique grant scopes for %s", routeKey).hasSize(scopes.size());
        return new Grant(grant.path("actor").textValue(), roles, scopes);
    }

    private static String expectedOwner(String routeKey) {
        return routeKey.equals("POST /api/auth/cpf/desafios")
                || routeKey.equals("POST /api/auth/cpf/verificar") ? "FUN" : "APP";
    }

    private static String expectedDecision(String routeKey) {
        return routeKey.equals(RETIRED_EMAIL_ROUTE) ? "DENY" : "ALLOW";
    }

    private static Set<Grant> expectedGrants(String routeKey) {
        if (routeKey.equals(RETIRED_EMAIL_ROUTE)) {
            return Set.of();
        }
        if (PUBLIC_ROUTES.contains(routeKey)) {
            return Set.of(new Grant("anonymous", Set.of(), Set.of()));
        }
        if (CUSTOMER_DECISION_ROUTES.contains(routeKey)) {
            return Set.of(new Grant("customer", Set.of(), Set.of("orders:decide:self")));
        }
        if (routeKey.equals(CUSTOMER_TRACKING_ROUTE)) {
            return Set.of(
                    new Grant("customer", Set.of(), Set.of("orders:read:self")),
                    new Grant("staff", Set.of("ADMIN", "MECANICO"), Set.of()));
        }
        Set<String> staffRoles = ADMIN_ONLY_ROUTES.contains(routeKey)
                ? Set.of("ADMIN")
                : Set.of("ADMIN", "MECANICO");
        return Set.of(new Grant("staff", staffRoles, Set.of()));
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

    private record Grant(String actor, Set<String> roles, Set<String> scopes) {}
}
