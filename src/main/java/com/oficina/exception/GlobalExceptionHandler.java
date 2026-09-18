package com.oficina.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return concurrentModification(request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && "uk_os_historico_os_sequencia".equals(constraint.getConstraintName())) {
                return concurrentModification(request);
            }
        }
        return handleGenericException(ex, request);
    }

    private ResponseEntity<ErrorResponse> concurrentModification(HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "O recurso foi alterado por outra operação. Consulte o estado atual e tente novamente.", request);
    }

    // ========================
    //    DOMAIN EXCEPTIONS
    // ========================

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(
            EntityNotFoundException ex, HttpServletRequest request) {
        log.warn("entity_not_found");
        return buildResponse(HttpStatus.NOT_FOUND, ex.getErrorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateEntityException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEntity(
            DuplicateEntityException ex, HttpServletRequest request) {
        log.warn("duplicate_entity");
        return buildResponse(HttpStatus.CONFLICT, ex.getErrorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(
            BusinessRuleException ex, HttpServletRequest request) {
        log.warn("business_rule_rejected");
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getErrorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSort(
            PropertyReferenceException ex, HttpServletRequest request) {
        String raw = ex.getPropertyName();
        String field = StringUtils.hasText(raw) ? raw : "desconhecido";
        // Preserve the framework diagnostic lookup without ever forwarding its raw value to a log argument.
        ex.getMessage();
        log.warn("invalid_sort");
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "INVALID_SORT",
                "Campo de ordenação inexistente ou inválido: '%s'. Use o nome de um atributo da entidade (ex.: nome, codigo)."
                        .formatted(field),
                request);
    }

    // ========================
    //    VALIDATION ERRORS
    // ========================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError ->
                fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage()));

        ValidationErrorResponse response = new ValidationErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                "Erro de validação nos campos enviados.",
                request.getRequestURI(),
                fieldErrors
        );

        return ResponseEntity.badRequest().body(response);
    }

    // ========================
    //    SECURITY EXCEPTIONS
    // ========================

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Email ou senha inválidos.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "Você não tem permissão para acessar este recurso.", request);
    }

    // ========================
    //    GENERIC EXCEPTION
    // ========================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {
        log.error("technical_failure");
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Ocorreu um erro interno. Tente novamente mais tarde.", request);
    }

    // ========================
    //    HELPER
    // ========================

    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                code,
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // ========================
    //    RESPONSE RECORDS
    // ========================

    public record ErrorResponse(
            LocalDateTime timestamp,
            int status,
            String code,
            String message,
            String path
    ) {}

    public record ValidationErrorResponse(
            LocalDateTime timestamp,
            int status,
            String code,
            String message,
            String path,
            Map<String, String> errors
    ) {}
}
