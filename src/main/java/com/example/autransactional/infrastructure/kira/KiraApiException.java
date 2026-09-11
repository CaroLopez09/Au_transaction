package com.example.autransactional.infrastructure.kira;

import lombok.Getter;

/** Error devuelto por Kira, ya normalizado. */
@Getter
public class KiraApiException extends RuntimeException {

    private final int statusCode;
    private final String code;
    private final String rawBody;

    public KiraApiException(int statusCode, String code, String message, String rawBody) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
        this.rawBody = rawBody;
    }

    public boolean isUnauthorized() {
        return statusCode == 401;
    }
}
