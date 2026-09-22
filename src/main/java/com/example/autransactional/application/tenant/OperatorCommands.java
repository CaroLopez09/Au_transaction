package com.example.autransactional.application.tenant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class OperatorCommands {

    private OperatorCommands() {
    }

    /**
     * Alta de un operador humano de la empresa.
     *
     * El rol viaja como el nombre de la constante (TREASURY_APPROVER, no tesoreria_approver): es lo
     * que el portal ya recibe en el JWT y en /api/auth/me, asi que no hay dos vocabularios.
     * La empresa NO viaja en el cuerpo: sale siempre de la sesion del ADMIN (aislamiento
     * multiempresa), y admitirla aqui seria ofrecer un campo que el servicio va a ignorar.
     */
    public record CreateOperator(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            /** En claro solo aqui: se guarda con BCrypt y no vuelve a salir en ninguna vista. */
            @NotBlank @Size(min = 12, max = 100) String password,
            @NotBlank String role) {
    }
}
