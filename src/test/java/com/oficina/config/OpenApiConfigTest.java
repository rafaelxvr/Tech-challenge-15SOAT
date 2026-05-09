package com.oficina.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void openAPI_contemInfoESeguranca() {
        OpenApiConfig cfg = new OpenApiConfig();
        ReflectionTestUtils.setField(cfg, "contextPath", "/api");

        OpenAPI api = cfg.openAPI();

        assertThat(api.getInfo().getTitle()).contains("Oficina");
        assertThat(api.getServers()).hasSize(1);
        assertThat(api.getServers().get(0).getUrl()).isEqualTo("http://localhost:8080/api");
        assertThat(api.getComponents().getSecuritySchemes()).containsKey("bearerAuth");
    }
}
