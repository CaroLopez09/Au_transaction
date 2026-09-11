package com.example.autransactional.infrastructure.identity;

import com.example.autransactional.domain.compliance.identity.DocumentOcr;
import com.example.autransactional.domain.compliance.identity.FaceComparator;
import com.example.autransactional.domain.compliance.identity.LivenessProvider;
import com.example.autransactional.domain.compliance.identity.SpeechTranscriber;
import com.example.autransactional.domain.compliance.identity.VerificationThresholds;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seleccion del proveedor biometrico.
 *
 * Los cuatro puertos se resuelven por configuracion, no por codigo: cambiar de proveedor
 * es cambiar bff.identity.provider y anadir la clase adaptadora. Ni el dominio ni los
 * controladores se enteran.
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityProviderConfig {

    @Bean
    public VerificationThresholds verificationThresholds(IdentityProperties properties) {
        return new VerificationThresholds(properties.minLivenessScore(),
                properties.minMatchScore(), properties.reviewFloor());
    }

    @Configuration
    @ConditionalOnProperty(prefix = "bff.identity", name = "provider",
            havingValue = "dev", matchIfMissing = true)
    static class DevProviders {

        @Bean
        public LivenessProvider livenessProvider(IdentityProperties properties) {
            return new DevLivenessProvider(properties);
        }

        @Bean
        public SpeechTranscriber speechTranscriber() {
            return new DevSpeechTranscriber();
        }

        @Bean
        public FaceComparator faceComparator() {
            return new DevFaceComparator();
        }

        @Bean
        public DocumentOcr documentOcr() {
            return new DevDocumentOcr();
        }
    }
}
