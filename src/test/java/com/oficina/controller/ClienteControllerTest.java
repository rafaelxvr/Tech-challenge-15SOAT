package com.oficina.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.config.JwtService;
import com.oficina.dto.ClienteRequest;
import com.oficina.dto.ClienteResponse;
import com.oficina.entity.TipoDocumento;
import com.oficina.service.ClienteService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ClienteController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import(MethodSecurityTestConfig.class)
class ClienteControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ClienteService clienteService;

    @MockBean
    JwtService jwtService;

    @MockBean
    UserDetailsService userDetailsService;

    private final UUID id = UUID.randomUUID();

    private ClienteResponse sampleResponse() {
        return new ClienteResponse(
                id, "João", TipoDocumento.CPF, "000.000.000-00", "00000000000",
                "j@j.com", "11999999999", null, null, null, null, null, null, null, true);
    }

    private ClienteRequest sampleRequest() {
        return new ClienteRequest(
                "João", TipoDocumento.CPF, "52998224725", "j@j.com", "11999999999",
                null, null, null, null, null, null, null);
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void listar_delegaAoServico() throws Exception {
        when(clienteService.listar(any())).thenReturn(
                new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/clientes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].nome").value("João"));
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void buscar_porId() throws Exception {
        when(clienteService.buscar(id)).thenReturn(sampleResponse());

        mockMvc.perform(get("/clientes/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void criar_retorna201() throws Exception {
        when(clienteService.criar(any(ClienteRequest.class))).thenReturn(sampleResponse());

        mockMvc.perform(post("/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Cliente cadastrado."));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void atualizar() throws Exception {
        when(clienteService.atualizar(eq(id), any(ClienteRequest.class))).thenReturn(sampleResponse());

        mockMvc.perform(put("/clientes/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void desativar() throws Exception {
        mockMvc.perform(delete("/clientes/{id}", id))
                .andExpect(status().isOk());

        verify(clienteService).desativar(id);
    }
}
