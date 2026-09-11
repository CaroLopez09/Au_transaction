package com.example.autransactional.infrastructure.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "bff.dev")
public record DevSeedProperties(
        @DefaultValue("false") boolean seed,
        @DefaultValue("Dev12345!") String seedPassword) {
}
