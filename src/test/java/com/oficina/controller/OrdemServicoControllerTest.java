package com.oficina.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.config.JwtService;
import com.oficina.dto.AcaoOrdemRequest;
import com.oficina.dto.AcompanhamentoOsResponse;
import com.oficina.dto.AprovacaoClienteRequest;
import com.oficina.dto.AtualizacaoStatusEmailRequest;
import com.oficina.dto.CriarOrdemServicoRequest;
import com.oficina.dto.DecisaoOrcamentoRequest;
import com.oficina.dto.ItemServicoOsRequest;
import com.oficina.dto.OrdemServicoDetalheResponse;
import com.oficina.dto.OrdemServicoResumoResponse;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.service.OrdemServicoService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrdemServicoController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import(MethodSecurityTestConfig.class)
class OrdemServicoControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    OrdemServicoService ordemServicoService;

    @MockBean
    JwtService jwtService;

    @MockBean
    UserDetailsService userDetailsService;

    private final UUID osId = UUID.randomUUID();
    private final UUID servicoId = UUID.randomUUID();

    private OrdemServicoDetalheResponse detalhe() {
        return new OrdemServicoDetalheResponse(
                osId,
                100L,
                StatusOrdemServico.RECEBIDA,
                BigDecimal.TEN,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.now(),
                new OrdemServicoDetalheResponse.ClienteResumo(UUID.randomUUID(), "C", "123"),
                new OrdemServicoDetalheResponse.VeiculoResumo(UUID.randomUUID(), "ABC1D23", "F", "M", 2020),
                List.of(),
                List.of(),
                List.of());
    }

    private CriarOrdemServicoRequest criarRequest() {
        return new CriarOrdemServicoRequest(
                "52998224725",
                "ABC1D23",
                "Fiat",
                "Uno",
                2020,
                List.of(new ItemServicoOsRequest(servicoId, 1, null)),
                List.of(),
                null);
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void criar() throws Exception {
        when(ordemServicoService.criar(any(CriarOrdemServicoRequest.class))).thenReturn(detalhe());

        mockMvc.perform(post("/ordens-servico")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(criarRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.numero").value(100));
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void listar() throws Exception {
        var resumo = new OrdemServicoResumoResponse(
                osId, 100L, StatusOrdemServico.RECEBIDA, "C", "ABC1D23",
                BigDecimal.TEN, LocalDateTime.now());
        when(ordemServicoService.listar(isNull(), any())).thenReturn(
                new PageImpl<>(List.of(resumo), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/ordens-servico"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void detalhar() throws Exception {
        when(ordemServicoService.buscarPorId(osId)).thenReturn(detalhe());

        mockMvc.perform(get("/ordens-servico/{id}", osId))
                .andExpect(status().isOk());
    }

    @Test
    void acompanhamento() throws Exception {
        var ac = new AcompanhamentoOsResponse(
                100L,
                StatusOrdemServico.RECEBIDA,
                BigDecimal.TEN,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "C",
                "ABC1D23",
                List.of());
        when(ordemServicoService.acompanhamentoPublico(100L)).thenReturn(ac);

        mockMvc.perform(get("/ordens-servico/{numero}/acompanhamento", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.numero").value(100));
    }

    @Test
    void aprovar() throws Exception {
        when(ordemServicoService.aprovarPeloCliente(eq(100L), any(AprovacaoClienteRequest.class)))
                .thenReturn(detalhe());

        mockMvc.perform(post("/ordens-servico/{numero}/aprovar", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AprovacaoClienteRequest("52998224725"))))
                .andExpect(status().isOk());
    }

    @Test
    void notificacaoOrcamentoAprovado() throws Exception {
        when(ordemServicoService.processarDecisaoOrcamento(eq(100L), any(DecisaoOrcamentoRequest.class)))
                .thenReturn(detalhe());

        var body = new DecisaoOrcamentoRequest(
                DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO, "52998224725", null);

        mockMvc.perform(post("/ordens-servico/{numero}/orcamento/notificacao", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void atualizarStatusViaEmail() throws Exception {
        when(ordemServicoService.atualizarStatusViaEmail(any(AtualizacaoStatusEmailRequest.class)))
                .thenReturn(detalhe());

        var body = new AtualizacaoStatusEmailRequest(
                100L, StatusOrdemServico.EM_DIAGNOSTICO, "oficina-email-status-token", "via email");

        mockMvc.perform(post("/ordens-servico/email/atualizar-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void iniciarDiagnostico() throws Exception {
        when(ordemServicoService.iniciarDiagnostico(eq(osId), any())).thenReturn(detalhe());

        mockMvc.perform(post("/ordens-servico/{id}/iniciar-diagnostico", osId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void enviarOrcamento() throws Exception {
        when(ordemServicoService.enviarOrcamento(eq(osId), any())).thenReturn(detalhe());

        mockMvc.perform(post("/ordens-servico/{id}/enviar-orcamento", osId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void finalizar() throws Exception {
        when(ordemServicoService.finalizar(eq(osId), any())).thenReturn(detalhe());

        mockMvc.perform(post("/ordens-servico/{id}/finalizar", osId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcaoOrdemRequest("ok"))))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"MECANICO"})
    void entregar() throws Exception {
        when(ordemServicoService.registrarEntrega(eq(osId), any())).thenReturn(detalhe());

        mockMvc.perform(post("/ordens-servico/{id}/entregar", osId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
