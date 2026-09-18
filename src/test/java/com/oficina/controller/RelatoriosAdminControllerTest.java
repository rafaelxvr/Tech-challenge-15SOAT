package com.oficina.controller;

import com.oficina.application.relatorio.*;
import com.oficina.config.JwtService;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.config.SecurityConfig;
import com.oficina.security.CustomerTokenValidator;
import com.oficina.security.StaffTokenValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=RelatoriosAdminController.class)
@Import(SecurityConfig.class)
class RelatoriosAdminControllerTest {
    @Autowired MockMvc mvc;
    @MockBean RelatoriosPort reports;
    @MockBean JwtService jwtService;
    @MockBean UserDetailsService userDetailsService;
    @MockBean CustomerTokenValidator customerTokenValidator;
    @MockBean StaffTokenValidator staffTokenValidator;
    private static final String URL = "/admin/relatorios/ordens";

    @Test @WithMockUser(roles="ADMIN") void emptyIsNaAndUsesBusinessZone() throws Exception {
        when(reports.consultar(LocalDate.of(2026,9,15),LocalDate.of(2026,9,16),ZoneId.of("America/Sao_Paulo")))
                .thenReturn(new RelatorioPeriodo(0,0,3,Map.of(StatusOrdemServico.EM_DIAGNOSTICO,new DuracaoStatus(BigDecimal.ZERO,0))));
        mvc.perform(get(URL).param("inicio","2026-09-15").param("fimExclusive","2026-09-16"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.duracoes.EM_DIAGNOSTICO.mediaSegundos").value("N/A"))
                .andExpect(jsonPath("$.data.duracoes.EM_DIAGNOSTICO.amostras").value(0))
                .andExpect(jsonPath("$.data.excluidas").value(3));
    }

    @Test @WithMockUser(roles="ADMIN") void equalOrReversedPeriodIsBadRequest() throws Exception {
        for (String end : new String[]{"2026-09-15","2026-09-14"}) {
            mvc.perform(get(URL).param("inicio","2026-09-15").param("fimExclusive",end)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(reports);
    }

    @Test @WithMockUser(roles="MECANICO") void mechanicCannotRead() throws Exception {
        mvc.perform(get(URL).param("inicio","2026-09-15").param("fimExclusive","2026-09-16")).andExpect(status().isForbidden());
        verifyNoInteractions(reports);
    }

    @Test @WithMockUser(roles="CLIENTE") void customerCannotRead() throws Exception {
        mvc.perform(get(URL).param("inicio","2026-09-15").param("fimExclusive","2026-09-16")).andExpect(status().isForbidden());
        verifyNoInteractions(reports);
    }

    @Test void anonymousCannotRead() throws Exception {
        mvc.perform(get(URL).param("inicio","2026-09-15").param("fimExclusive","2026-09-16")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reports);
    }

    @Test @WithMockUser(roles="ADMIN") void malformedOrMissingDateIsBadRequest() throws Exception {
        mvc.perform(get(URL).param("inicio","not-a-date").param("fimExclusive","2026-09-16")).andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("inicio","2026-09-15")).andExpect(status().isBadRequest());
        verifyNoInteractions(reports);
    }
}
