package com.example.autransactional.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "bff.security")
public record BffSecurityProperties(
        String jwtSecret,
        @DefaultValue("autransactional-bff") String jwtIssuer,
        @DefaultValue("28800000") long tokenExpirationMs) {
}
