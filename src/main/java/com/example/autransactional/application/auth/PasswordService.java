package com.example.autransactional.application.auth;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.PasswordHistoryRepository;
import com.example.autransactional.infrastructure.security.PasswordGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Punto unico para: generar contrasenas temporales, validar la fuerza de una contrasena elegida
 * por el usuario y aplicar la politica de no reutilizar las ultimas {@link #HISTORY_DEPTH}
 * contrasenas. Lo usan el alta de operadores, el reset administrativo, el cambio obligatorio tras
 * login y la recuperacion por OTP: todos comparten la misma regla, no cada uno la suya.
 */
@Service
public class PasswordService {

    /** Contrasenas recientes (incluida la activa) que no se pueden repetir. */
    static final int HISTORY_DEPTH = 2;
    static final String REUSE_MESSAGE =
            "La nueva contrasena no puede ser igual a ninguna de las ultimas " + HISTORY_DEPTH
                    + " contrasenas utilizadas. Ingresa una contrasena diferente.";

    private final OperatorUserRepository users;
    private final PasswordHistoryRepository history;
    private final PasswordEncoder encoder;
    private final PasswordGenerator generator;

    public PasswordService(OperatorUserRepository users, PasswordHistoryRepository history,
                           PasswordEncoder encoder, PasswordGenerator generator) {
        this.users = users;
        this.history = history;
        this.encoder = encoder;
        this.generator = generator;
    }

    /** Contrasena aleatoria de un solo uso para alta o reset administrativo. Nunca se loguea. */
    public String generateTemporary() {
        return generator.generate();
    }

    /**
     * Fija una contrasena temporal generada por el sistema (alta o reset). No aplica la politica
     * de no-reutilizacion: es aleatoria, no elegida por nadie, y el usuario debera cambiarla en su
     * proximo ingreso.
     */
    @Transactional
    public void applyTemporaryPassword(OperatorUser user, String rawTempPassword, boolean resetByAdmin) {
        users.updatePassword(user.id(), encoder.encode(rawTempPassword), true, resetByAdmin);
    }

    /**
     * Aplica una contrasena elegida por el propio usuario (cambio obligatorio, cambio voluntario
     * o recuperacion por OTP). Valida fuerza y no-reutilizacion salvo que la contrasena temporal
     * vigente provenga de un reset de administrador: en ese caso el usuario puede volver a su
     * contrasena de siempre, ya que no fue su decision perderla.
     */
    @Transactional
    public void applyChosenPassword(OperatorUser user, String newPassword) {
        assertStrongEnough(newPassword);
        if (!user.passwordResetByAdmin()) {
            assertNotReused(user, newPassword);
        }
        archiveCurrent(user);
        users.updatePassword(user.id(), encoder.encode(newPassword), false, false);
    }

    private void assertNotReused(OperatorUser user, String newPassword) {
        if (user.passwordHash() != null && encoder.matches(newPassword, user.passwordHash())) {
            throw new DomainException(REUSE_MESSAGE);
        }
        if (HISTORY_DEPTH <= 1) {
            return;
        }
        for (String hash : history.recentHashes(user.id(), HISTORY_DEPTH - 1)) {
            if (encoder.matches(newPassword, hash)) {
                throw new DomainException(REUSE_MESSAGE);
            }
        }
    }

    private void archiveCurrent(OperatorUser user) {
        if (user.passwordHash() == null || user.passwordHash().isBlank()) {
            return;
        }
        history.archive(user.id(), user.passwordHash());
        history.trim(user.id(), Math.max(HISTORY_DEPTH - 1, 0));
    }

    /** Misma politica de fuerza que el resto de la plataforma: min 12, mayuscula, minuscula, digito, simbolo. */
    public void assertStrongEnough(String password) {
        if (password == null || password.length() < 12) {
            throw new DomainException("La contrasena debe tener al menos 12 caracteres.");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new DomainException("La contrasena debe contener al menos una mayuscula.");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new DomainException("La contrasena debe contener al menos una minuscula.");
        }
        if (!password.matches(".*[0-9].*")) {
            throw new DomainException("La contrasena debe contener al menos un numero.");
        }
        if (!password.matches(".*[@$!%*?&#^()_+\\-=].*")) {
            throw new DomainException("La contrasena debe contener al menos un caracter especial (@$!%*?&#).");
        }
    }
}
