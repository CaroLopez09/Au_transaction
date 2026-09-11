package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.compliance.RfiAnswerRejectedException;
import com.example.autransactional.domain.compliance.identity.IdentityErrorCode;
import com.example.autransactional.domain.compliance.identity.IdentityException;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.infrastructure.kira.KiraApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduce los errores a una forma unica para el frontend. La API de Kira convive con varias
 * formas de error; el BFF no las propaga crudas: entrega codigo estable y mensaje accionable.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    /** Errores por item_id: el portal los pinta junto a cada campo y no marca ninguno como guardado. */
    @ExceptionHandler(RfiAnswerRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRfiAnswer(RfiAnswerRejectedException e) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "rfi_answer_rejected", e.getMessage(), e.itemErrors());
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, Object>> handleDomain(DomainException e) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "business_rule_violation", e.getMessage(), null);
    }

    /**
     * La libreria de onboarding usa 'code' para elegir el mensaje que muestra al usuario y
     * cae a 'message' cuando el codigo le es desconocido. Respetar esa forma es lo que hace
     * que la persona lea "acercate a la camara" en vez de un error tecnico.
     */
    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleIdentity(IdentityException e) {
        HttpStatus status = switch (e.getCode()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case SESSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        if (e.getCode() == IdentityErrorCode.SERVER_ERROR) {
            log.error("Error de verificacion de identidad", e);
        }
        return body(status, e.getCode().name(), e.getMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "forbidden", "No tienes permiso para esta accion.", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(err -> fields.put(err.getField(), err.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "validation_error", "Datos invalidos.", fields);
    }

    @ExceptionHandler(KiraApiException.class)
    public ResponseEntity<Map<String, Object>> handleKira(KiraApiException e) {
        log.warn("Error de KiraFin ({} {}): {}", e.getStatusCode(), e.getCode(), e.getMessage());
        HttpStatus status = e.getStatusCode() >= 500 || e.isUnauthorized()
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.UNPROCESSABLE_CONTENT;
        return body(status, "kira_" + (e.getCode() != null ? e.getCode() : "error"), e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Error no controlado", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Ocurrio un error inesperado.", null);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message,
                                                     Object details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        if (details != null) {
            payload.put("details", details);
        }
        return ResponseEntity.status(status).body(payload);
    }
}
