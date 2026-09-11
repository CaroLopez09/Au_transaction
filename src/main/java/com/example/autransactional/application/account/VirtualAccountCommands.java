package com.example.autransactional.application.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public final class VirtualAccountCommands {

    private VirtualAccountCommands() {
    }

    /**
     * Apertura de cuenta virtual.
     *
     * El banco no se pide: lo fija la configuracion del entorno, porque el valor valido
     * depende de si se apunta al sandbox o a produccion y equivocarlo devuelve
     * 400 "Invalid bank".
     */
    public record OpenAccount(
            @Size(max = 255) String description,
            /* fiat o crypto. INMUTABLE una vez creada la cuenta. */
            String mode,
            @Size(max = 10) String currency) {
    }

    /** Solo sandbox. En produccion, Kira responde 403. */
    public record SimulateDeposit(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @Pattern(regexp = "wire|ach") String paymentType) {
    }
}
