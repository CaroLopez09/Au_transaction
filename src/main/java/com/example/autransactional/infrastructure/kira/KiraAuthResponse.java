package com.example.autransactional.infrastructure.kira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Envoltura estandar { message, data } de POST /auth. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KiraAuthResponse(String message, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("token_type") String tokenType) {
    }
}
