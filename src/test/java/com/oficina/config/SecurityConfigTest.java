package com.oficina.config;

import com.oficina.security.CustomerTokenValidator;
import com.oficina.security.IdentidadeAutenticada;
import com.oficina.security.StaffTokenValidator;
import com.oficina.security.TipoPrincipal;
import com.oficina.support.TokenFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {
    @Autowired MockMvc mvc;
    @MockBean CustomerTokenValidator customers;
    @MockBean StaffTokenValidator staff;
    @MockBean UserDetailsService userDetails;
    private final TokenFixtures fixtures = new TokenFixtures();

    @BeforeEach void identities() {
        when(customers.validar(anyString())).thenReturn(new IdentidadeAutenticada(TipoPrincipal.CUSTOMER,
                TokenFixtures.CUSTOMER_ID, Set.of("SCOPE_orders:read:self"), 1));
        when(staff.validar(anyString())).thenReturn(new IdentidadeAutenticada(TipoPrincipal.STAFF,
                TokenFixtures.STAFF_ID, Set.of("ROLE_MECANICO"), 0));
    }

    @Test void missingCredentialsReturn401() throws Exception {
        mvc.perform(get("/internal/probe")).andExpect(status().isUnauthorized());
    }

    @Test void customerCannotUseStaffCatchAll() throws Exception {
        mvc.perform(get("/internal/probe").header("Authorization", "Bearer " +
                fixtures.customer(TokenFixtures.CUSTOMER_ID, 1, "staging", TokenFixtures.NOW.plusSeconds(60))))
                .andExpect(status().isForbidden());
    }

    @Test void staffCanUseOperationalRouteButNeedsAdminRoleForAdminRoute() throws Exception {
        String bearer = "Bearer " + fixtures.staff("access", "staging");
        mvc.perform(get("/internal/probe").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/admin/probe").header("Authorization", bearer)).andExpect(status().isForbidden());
    }

    @Test void authenticationDoesNotSurviveIntoNextRequest() throws Exception {
        mvc.perform(get("/internal/probe").header("Authorization", "Bearer " + fixtures.staff("access", "staging")))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/probe")).andExpect(status().isUnauthorized());
    }

    @RestController
    static class ProbeController {
        @GetMapping({"/internal/probe", "/admin/probe"}) String probe() { return "ok"; }
    }
}
