package com.oficina.saga.adapter.in.rest;

import com.oficina.config.JwtService;
import com.oficina.saga.application.CicloOsSagaService;
import com.oficina.saga.config.SagaProperties;
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

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D18-R: with the saga switch off the inspection route answers 404 and the saga service is never touched. */
@WebMvcTest(controllers = SagaController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import(MethodSecurityTestConfig.class)
class SagaControllerDesligadaTest {
    @Autowired MockMvc mockMvc;
    @MockBean CicloOsSagaService ciclo;
    @MockBean SagaProperties propriedades;
    @MockBean JwtService jwtService;
    @MockBean UserDetailsService userDetailsService;

    @Test @WithMockUser(roles = "ADMIN")
    void semSagaLigadaRetorna404SemTocarNoServico() throws Exception {
        mockMvc.perform(get("/ordens-servico/{id}/saga", UUID.randomUUID())).andExpect(status().isNotFound());
        verifyNoInteractions(ciclo);
    }
}
