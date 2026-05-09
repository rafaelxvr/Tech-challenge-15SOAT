package com.oficina.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.config.JwtService;
import com.oficina.dto.ServicoRequest;
import com.oficina.dto.ServicoResponse;
import com.oficina.service.ServicoService;
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

@WebMvcTest(controllers = ServicoCatalogoController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import(MethodSecurityTestConfig.class)
class ServicoCatalogoControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ServicoService servicoService;

    @MockBean
    JwtService jwtService;

    @MockBean
    UserDetailsService userDetailsService;

    private final UUID sid = UUID.randomUUID();

    private ServicoResponse sample() {
        return new ServicoResponse(sid, "Troca óleo", "desc", new BigDecimal("100.00"), 60, true);
    }

    private ServicoRequest request() {
        return new ServicoRequest("Troca óleo", "desc", new BigDecimal("100.00"), 60);
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void listar() throws Exception {
        when(servicoService.listar(any())).thenReturn(
                new PageImpl<>(List.of(sample()), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/servicos"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void buscar() throws Exception {
        when(servicoService.buscar(sid)).thenReturn(sample());

        mockMvc.perform(get("/servicos/{id}", sid))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void criar() throws Exception {
        when(servicoService.criar(any(ServicoRequest.class))).thenReturn(sample());

        mockMvc.perform(post("/servicos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void atualizar() throws Exception {
        when(servicoService.atualizar(eq(sid), any(ServicoRequest.class))).thenReturn(sample());

        mockMvc.perform(put("/servicos/{id}", sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void desativar() throws Exception {
        mockMvc.perform(delete("/servicos/{id}", sid))
                .andExpect(status().isOk());
        verify(servicoService).desativar(sid);
    }
}
