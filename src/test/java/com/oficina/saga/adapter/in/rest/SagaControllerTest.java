package com.oficina.saga.adapter.in.rest;

import com.oficina.config.JwtService;
import com.oficina.saga.application.CicloOsSagaService;
import com.oficina.saga.application.SagaDetalhe;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SagaController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import(MethodSecurityTestConfig.class)
class SagaControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean CicloOsSagaService ciclo;
    @MockBean JwtService jwtService;
    @MockBean UserDetailsService userDetailsService;

    @Test @WithMockUser(roles = "MECANICO")
    void devolveEstadoETrilha() throws Exception {
        UUID os = UUID.randomUUID();
        when(ciclo.detalhar(os)).thenReturn(Optional.of(new SagaDetalhe(UUID.randomUUID(), os, "COMPENSANDO",
                "PAGAMENTO", 1, Instant.parse("2026-09-15T12:05:00Z"), "PAGAMENTO_RECUSADO", List.of("CANCELAR_ORCAMENTO"),
                List.of(new SagaDetalhe.Passo(1, "DIAGNOSTICO", "ESTADO:AGUARDANDO_DIAGNOSTICO", "AGUARDANDO_DIAGNOSTICO",
                        Instant.parse("2026-09-15T12:00:00Z"))))));
        mockMvc.perform(get("/ordens-servico/{id}/saga", os))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estado").value("COMPENSANDO"))
                .andExpect(jsonPath("$.data.compensacoesPendentes[0]").value("CANCELAR_ORCAMENTO"))
                .andExpect(jsonPath("$.data.passos[0].evento").value("ESTADO:AGUARDANDO_DIAGNOSTICO"));
    }

    @Test @WithMockUser(roles = "ADMIN")
    void semSagaRetorna404() throws Exception {
        UUID os = UUID.randomUUID();
        when(ciclo.detalhar(os)).thenReturn(Optional.empty());
        mockMvc.perform(get("/ordens-servico/{id}/saga", os)).andExpect(status().isNotFound());
    }

    @Test @WithMockUser(authorities = "SCOPE_orders:read:self")
    void clienteNaoAcessa() throws Exception {
        mockMvc.perform(get("/ordens-servico/{id}/saga", UUID.randomUUID())).andExpect(status().isForbidden());
    }
}
