package com.example.autransactional.application.auth;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.infrastructure.email.EmailNotificationService;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recuperacion de contrasena por OTP: "olvide mi contrasena".
 *
 * BankVision delega la generacion/envio/validacion del OTP por completo en el servicio externo de
 * correo. Ese mismo servicio expone endpoints de OTP, pero exigen un token de autorizacion que
 * este entorno de pruebas no tiene configurado: sin ese token el proveedor rechaza la peticion con
 * 400. Por eso se usa el fallback ya acordado con el usuario: un codigo de 6 digitos y un token de
 * reseteo internos, ambos con TTL de 10 minutos (mismo valor que BankVision usa para su token de
 * recuperacion), enviados por la via de plantillas de correo que si funciona sin ese token.
 */
@Service
public class ForgotPasswordService {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private final OperatorUserRepository users;
    private final EmailNotificationService email;
    private final PasswordService passwords;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingOtp> pendingOtps = new ConcurrentHashMap<>();
    private final Map<String, ResetToken> resetTokens = new ConcurrentHashMap<>();

    public ForgotPasswordService(OperatorUserRepository users, EmailNotificationService email,
                                 PasswordService passwords) {
        this.users = users;
        this.email = email;
        this.passwords = passwords;
    }

    /**
     * Genera un OTP de 6 digitos y lo envia por correo. Nunca revela si el correo existe: siempre
     * responde igual, exista o no la cuenta.
     */
    public void start(String rawEmail) {
        String normalized = normalize(rawEmail);
        users.findByEmail(normalized).ifPresent(user -> {
            String otp = newOtp();
            pendingOtps.put(normalized, new PendingOtp(user.id(), otp, Instant.now().plusMillis(TTL_MILLIS)));
            email.sendRecoveryOtpEmail(user.email(), user.fullName(), otp);
        });
    }

    /**
     * Valida el OTP y, si es correcto, emite un token interno de restablecimiento de un solo uso
     * valido por 10 minutos. El OTP se consume en el primer intento correcto.
     */
    public String verify(String rawEmail, String otp) {
        String normalized = normalize(rawEmail);
        PendingOtp pending = pendingOtps.get(normalized);
        if (pending == null || pending.expiresAt().isBefore(Instant.now()) || !pending.otp().equals(otp)) {
            throw new DomainException("El codigo ingresado no es valido o expiro.");
        }
        pendingOtps.remove(normalized);

        String token = newToken();
        resetTokens.put(token, new ResetToken(pending.userId(), Instant.now().plusMillis(TTL_MILLIS)));
        return token;
    }

    /** Consume el token de restablecimiento y aplica la nueva contrasena elegida por el usuario. */
    public void reset(String resetToken, String newPassword, String confirmNewPassword) {
        if (newPassword == null || !newPassword.equals(confirmNewPassword)) {
            throw new DomainException("Las contrasenas no coinciden.");
        }
        ResetToken token = resetTokens.remove(resetToken);
        if (token == null || token.expiresAt().isBefore(Instant.now())) {
            throw new DomainException("El enlace de restablecimiento expiro. Solicita un nuevo codigo.");
        }
        OperatorUser user = users.findById(token.userId())
                .orElseThrow(() -> new DomainException("El usuario no existe."));
        passwords.applyChosenPassword(user, newPassword);
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private String newOtp() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record PendingOtp(String userId, String otp, Instant expiresAt) {
    }

    private record ResetToken(String userId, Instant expiresAt) {
    }
}
