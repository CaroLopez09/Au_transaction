package com.example.autransactional.domain.compliance.identity;

import lombok.Getter;

/** Error de verificacion con un codigo que el frontend sabe interpretar. */
@Getter
public class IdentityException extends RuntimeException {

    private final IdentityErrorCode code;

    public IdentityException(IdentityErrorCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public IdentityException(IdentityErrorCode code, String message) {
        super(message);
        this.code = code;
    }
}
