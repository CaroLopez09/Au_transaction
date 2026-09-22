package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.tenant.IdentityVerificationService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.multipart.MultipartFile;

/** Recibe el material biometrico bajo una credencial restringida, no una sesion del portal. */
@RestController
@RequestMapping("/api/operators")
public class IdentityVerificationController {

    private final IdentityVerificationService identity;

    public IdentityVerificationController(IdentityVerificationService identity) { this.identity = identity; }

    @PostMapping(value = "/{id}/verify-identity", consumes = "multipart/form-data")
    public IdentityVerificationService.Result verify(@PathVariable String id,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestParam MultipartFile documentFrontImage, @RequestParam MultipartFile documentBackImage,
            @RequestParam MultipartFile selfieImage, @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String countryCode, @RequestParam boolean biometricConsent,
            @RequestParam(required = false) String livenessSessionId) {
        return identity.submit(bearer(authorization), id, documentFrontImage, documentBackImage, selfieImage,
                documentType, countryCode, biometricConsent, livenessSessionId);
    }

    private static String bearer(String value) {
        if (value == null || !value.startsWith("Bearer ")) throw new IllegalArgumentException("Falta el reto.");
        return value.substring(7);
    }
}