package com.oficina.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.config.JwtService;
import com.oficina.dto.VeiculoRequest;
import com.oficina.dto.VeiculoResponse;
import com.oficina.service.VeiculoService;
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

@WebMvcTest(controllers = VeiculoController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import(MethodSecurityTestConfig.class)
class VeiculoControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    VeiculoService veiculoService;

    @MockBean
    JwtService jwtService;

    @MockBean
    UserDetailsService userDetailsService;

    private final UUID vid = UUID.randomUUID();
    private final UUID clienteId = UUID.randomUUID();

    private VeiculoResponse sample() {
        return new VeiculoResponse(vid, "ABC1D23", "Fiat", "Uno", 2020, "Branco", null, clienteId, true);
    }

    private VeiculoRequest request() {
        return new VeiculoRequest("ABC1D23", "Fiat", "Uno", 2020, "Branco", null, clienteId);
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void listar_semClienteId() throws Exception {
        when(veiculoService.listar(any())).thenReturn(
                new PageImpl<>(List.of(sample()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/veiculos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].placa").value("ABC1D23"));
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void listar_porClienteId() throws Exception {
        when(veiculoService.listarPorCliente(eq(clienteId), any())).thenReturn(
                new PageImpl<>(List.of(sample()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/veiculos").param("clienteId", clienteId.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void buscar() throws Exception {
        when(veiculoService.buscar(vid)).thenReturn(sample());

        mockMvc.perform(get("/veiculos/{id}", vid))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void criar() throws Exception {
        when(veiculoService.criar(any(VeiculoRequest.class))).thenReturn(sample());

        mockMvc.perform(post("/veiculos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void atualizar() throws Exception {
        when(veiculoService.atualizar(eq(vid), any(VeiculoRequest.class))).thenReturn(sample());

        mockMvc.perform(put("/veiculos/{id}", vid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void desativar() throws Exception {
        mockMvc.perform(delete("/veiculos/{id}", vid))
                .andExpect(status().isOk());
        verify(veiculoService).desativar(vid);
    }
}
