package com.oficina.exception;

import com.oficina.controller.AuthController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Test void optimisticLockReturnsConcurrentModification() {
        var response = handler.handleOptimisticLock(
                new org.springframework.orm.ObjectOptimisticLockingFailureException("OrdemServico", UUID.randomUUID()),
                new MockHttpServletRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test void onlyNamedSequenceConstraintReturnsConflict() {
        var constraint = new org.hibernate.exception.ConstraintViolationException(
                "duplicate sequence", new java.sql.SQLException("duplicate", "23505"), "uk_os_historico_os_sequencia");
        var response = handler.handleIntegrityViolation(
                new org.springframework.dao.DataIntegrityViolationException("write failed", new RuntimeException(constraint)),
                new MockHttpServletRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test void unrelatedIntegrityOrMessageMatchingDoesNotReturnConflict() {
        var constraint = new org.hibernate.exception.ConstraintViolationException(
                "uk_os_historico_os_sequencia", new java.sql.SQLException("duplicate", "23505"), "other_unique");
        var response = handler.handleIntegrityViolation(
                new org.springframework.dao.DataIntegrityViolationException("uk_os_historico_os_sequencia", constraint),
                new MockHttpServletRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Mock
    private PropertyReferenceException propertyReferenceException;

    @RestController
    static class ValidacaoStub {
        @PostMapping("/__validate")
        void post(@Valid @RequestBody AuthController.LoginRequest body) {
        }
    }

    @Test
    void entityNotFound_retorna404() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/api/clientes/1");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> res = handler.handleEntityNotFound(
                new EntityNotFoundException("Cliente", UUID.randomUUID()), req);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().code()).isEqualTo("ENTITY_NOT_FOUND");
    }

    @Test
    void duplicateEntity_retorna409() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> res = handler.handleDuplicateEntity(
                new DuplicateEntityException("X", "campo", "v"), req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void businessRule_retorna422() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> res = handler.handleBusinessRule(
                new BusinessRuleException("regra"), req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void invalidSort_retorna400() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        when(propertyReferenceException.getPropertyName()).thenReturn("campoInvalido");
        when(propertyReferenceException.getMessage()).thenReturn("no property campoInvalido");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> res =
                handler.handleInvalidSort(propertyReferenceException, req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("INVALID_SORT");
        assertThat(res.getBody().message()).contains("campoInvalido");
    }

    @Test
    void invalidSort_semNomeDeCampo_usaDesconhecidoNaMensagem() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        when(propertyReferenceException.getPropertyName()).thenReturn(null);
        when(propertyReferenceException.getMessage()).thenReturn("erro");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> res =
                handler.handleInvalidSort(propertyReferenceException, req);
        assertThat(res.getBody().message()).contains("desconhecido");
    }

    @Test
    void methodArgumentNotValid_montaMapaDeErros() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ValidacaoStub())
                .setControllerAdvice(handler)
                .build();

        mvc.perform(post("/__validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void badCredentials_retorna401() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> res = handler.handleBadCredentials(
                new BadCredentialsException("x"), req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void accessDenied_retorna403() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> res = handler.handleAccessDenied(
                new AccessDeniedException("x"), req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void genericException_retorna500() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> res = handler.handleGenericException(
                new RuntimeException("boom"), req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
