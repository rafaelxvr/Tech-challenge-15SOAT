package com.oficina.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.config.JwtService;
import com.oficina.dto.PecaRequest;
import com.oficina.dto.PecaResponse;
import com.oficina.service.PecaService;
import com.oficina.support.MethodSecurityTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PecaController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import(MethodSecurityTestConfig.class)
class PecaControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    PecaService pecaService;

    @MockBean
    JwtService jwtService;

    @MockBean
    UserDetailsService userDetailsService;

    private final UUID pid = UUID.randomUUID();

    private PecaResponse sample() {
        return new PecaResponse(
                pid, "P001", "Filtro", "desc", new BigDecimal("10.00"),
                5, 1, "UN", true);
    }

    private PecaRequest request() {
        return new PecaRequest("P001", "Filtro", "desc", new BigDecimal("10.00"), 5, 1, "UN");
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void listar() throws Exception {
        when(pecaService.listar(any())).thenReturn(
                new PageImpl<>(List.of(sample()), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/pecas"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void buscar() throws Exception {
        when(pecaService.buscar(pid)).thenReturn(sample());

        mockMvc.perform(get("/pecas/{id}", pid))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void criar() throws Exception {
        when(pecaService.criar(any(PecaRequest.class))).thenReturn(sample());

        mockMvc.perform(post("/pecas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void atualizar() throws Exception {
        when(pecaService.atualizar(eq(pid), any(PecaRequest.class))).thenReturn(sample());

        mockMvc.perform(put("/pecas/{id}", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void desativar() throws Exception {
        mockMvc.perform(delete("/pecas/{id}", pid))
                .andExpect(status().isOk());
        verify(pecaService).desativar(pid);
    }
}
