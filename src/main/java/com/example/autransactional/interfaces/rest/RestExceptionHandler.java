package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.compliance.RfiAnswerRejectedException;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.infrastructure.kira.KiraApiException;
import com.example.autransactional.infrastructure.kira.KiraNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
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

    /** Peticion mal formada: parte o parametro ausente, JSON ilegible o tipo equivocado. */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        String detail = switch (e) {
            case MissingServletRequestPartException m -> "Falta la parte '" + m.getRequestPartName() + "'.";
            case MissingServletRequestParameterException m -> "Falta el parametro '" + m.getParameterName() + "'.";
            case MethodArgumentTypeMismatchException m -> "Valor invalido para '" + m.getName() + "'.";
            default -> "El cuerpo de la peticion no es un JSON valido.";
        };
        return body(HttpStatus.BAD_REQUEST, "validation_error", detail, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                "El archivo supera el tamano permitido (30 MB por archivo).", null);
    }

    /** Ruta inexistente: 404, no "error inesperado". */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", "La ruta no existe.", null);
    }

    @ExceptionHandler(KiraNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleKiraNotConfigured(KiraNotConfiguredException e) {
        log.error("Integracion con Kira sin configurar: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "kira_not_configured",
                "La integracion con Kira no esta configurada en este entorno.", null);
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
