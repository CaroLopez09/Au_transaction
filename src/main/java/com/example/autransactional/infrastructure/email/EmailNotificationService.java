package com.example.autransactional.infrastructure.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cliente del servicio corporativo de correo (mismo proveedor que la biometria,
 * host {@code pruebas.bankvision.com}). El BFF nunca envia SMTP directo: delega el envio de
 * correo en este servicio externo, igual que hace BankVision.
 *
 * El endpoint de OTP nativo del proveedor (envio y verificacion de PIN) exige un token de
 * autorizacion que este entorno de pruebas no tiene configurado: por eso la recuperacion de
 * contrasena usa el fallback acordado (ver {@code ForgotPasswordService}) y este cliente solo
 * envia el codigo por la misma via de plantillas que ya funciona sin ese token.
 *
 * Un fallo de red o de configuracion NUNCA debe tumbar el flujo que origino el correo (alta de
 * usuario, reset administrativo): se registra el error y se continua. Para el correo de OTP de
 * recuperacion si se propaga el error: sin ese correo el usuario no puede completar el flujo.
 */
@Component
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);
    private static final String TEMPLATE_PATH = "/email/sendEmailWithTemplateHtml";
    private static final String CREATED_PASSWORD_TEMPLATE = "CREATED_PASSWORD";
    private static final String RESET_PASSWORD_TEMPLATE = "RESET_PASSWORD";
    private static final String OTP_RECOVERY_TEMPLATE = "OTP_RECOVERY";

    private final RestClient restClient;
    private final EmailProperties properties;

    public EmailNotificationService(RestClient emailRestClient, EmailProperties properties) {
        this.restClient = emailRestClient;
        this.properties = properties;
    }

    /** Correo de bienvenida con la contrasena temporal recien generada al crear la cuenta. */
    public void sendAccountCreatedEmail(String to, String fullName, String tempPassword) {
        sendTemplate(to, "Credenciales de acceso - AU Transactional", CREATED_PASSWORD_TEMPLATE,
                credentialsData(fullName, to, tempPassword));
    }

    /** Correo con la nueva contrasena temporal tras un reset administrativo. */
    public void sendPasswordResetEmail(String to, String fullName, String tempPassword) {
        sendTemplate(to, "Tu contrasena fue restablecida - AU Transactional", RESET_PASSWORD_TEMPLATE,
                credentialsData(fullName, to, tempPassword));
    }

    /**
     * Envia el codigo OTP de recuperacion (generado por {@code ForgotPasswordService}) por la
     * misma via de plantillas de correo. A diferencia de los otros correos, un fallo aqui SI debe
     * saberlo quien solicito la recuperacion: sin el correo no hay forma de continuar el flujo.
     */
    public void sendRecoveryOtpEmail(String to, String fullName, String otp) {
        if (!properties.configured()) {
            throw new IllegalStateException("Servicio de correo no configurado en este entorno.");
        }
        Map<String, String> data = new LinkedHashMap<>();
        String nombre = fullName == null || fullName.isBlank() ? to : fullName;
        data.put("nombre_completo", nombre);
        data.put("nombreCompleto", nombre);
        data.put("correo", to);
        data.put("email", to);
        // Nunca se loguea: solo viaja en el body HTTPS al proveedor de correo, una sola vez.
        data.put("codigo_otp", otp);
        data.put("codigoOtp", otp);
        data.put("empresa", "AU Transactional");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", to);
        body.put("asunto", "Codigo de verificacion - AU Transactional");
        body.put("template", OTP_RECOVERY_TEMPLATE);
        body.put("templateName", OTP_RECOVERY_TEMPLATE);
        body.put("data", data);
        try {
            restClient.post().uri(TEMPLATE_PATH).headers(this::authHeaders).body(body).retrieve().body(String.class);
            log.info("Correo '{}' enviado a {}", OTP_RECOVERY_TEMPLATE, to);
        } catch (HttpStatusCodeException e) {
            log.warn("Error HTTP enviando OTP a {}: {}", to, e.getResponseBodyAsString());
            throw new IllegalStateException("No fue posible enviar el codigo de verificacion. Intenta nuevamente.");
        } catch (RuntimeException e) {
            log.error("Fallo enviando OTP a {}: {}", to, e.getMessage(), e);
            throw new IllegalStateException("No fue posible enviar el codigo de verificacion en este momento.");
        }
    }

    private void sendTemplate(String to, String subject, String template, Map<String, String> data) {
        if (!properties.configured()) {
            log.warn("Servicio de correo no configurado: se omite el envio de '{}' a {}", template, to);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", to);
        body.put("asunto", subject);
        body.put("template", template);
        body.put("templateName", template);
        body.put("data", data);
        try {
            restClient.post().uri(TEMPLATE_PATH).headers(this::authHeaders).body(body).retrieve().body(String.class);
            log.info("Correo '{}' enviado a {}", template, to);
        } catch (HttpStatusCodeException e) {
            // El correo es un efecto secundario: no debe tumbar la creacion/reset del usuario.
            log.warn("Error HTTP enviando correo '{}' a {}: {}", template, to, e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            log.error("Fallo enviando correo '{}' a {}: {}", template, to, e.getMessage(), e);
        }
    }

    private Map<String, String> credentialsData(String fullName, String email, String tempPassword) {
        Map<String, String> data = new LinkedHashMap<>();
        String nombre = fullName == null || fullName.isBlank() ? email : fullName;
        data.put("nombre_completo", nombre);
        data.put("nombreCompleto", nombre);
        data.put("correo", email);
        data.put("email", email);
        // Nunca se loguea: solo viaja en el body HTTPS al proveedor de correo, una sola vez.
        data.put("password_temporal", tempPassword);
        data.put("passwordTemporal", tempPassword);
        data.put("empresa", "AU Transactional");
        return data;
    }

    private void authHeaders(org.springframework.http.HttpHeaders headers) {
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            headers.setBearerAuth(properties.apiKey());
        }
    }
}
