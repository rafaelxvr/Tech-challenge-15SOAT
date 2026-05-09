package com.oficina.controller;

import com.oficina.config.JwtService;
import com.oficina.dto.MetricasTempoResponse;
import com.oficina.service.MetricasService;
import com.oficina.support.MethodSecurityTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MetricasAdminController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import(MethodSecurityTestConfig.class)
class MetricasAdminControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    MetricasService metricasService;

    @MockBean
    JwtService jwtService;

    @MockBean
    UserDetailsService userDetailsService;

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void tempoExecucao() throws Exception {
        when(metricasService.tempoMedioExecucao()).thenReturn(
                new MetricasTempoResponse(30.0, 45.5, 10L));

        mockMvc.perform(get("/admin/metricas/tempo-execucao-servicos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ordensComTempoMedido").value(10));
    }
}
