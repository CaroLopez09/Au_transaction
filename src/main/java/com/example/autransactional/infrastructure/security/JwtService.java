package com.example.autransactional.infrastructure.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.Role;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Emite y verifica el JWT propio del BFF. Nada de esto viaja a Kira. */
@Service
public class JwtService {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_USER_ID = "uid";

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final BffSecurityProperties properties;

    public JwtService(BffSecurityProperties properties) {
        this.properties = properties;
        this.algorithm = Algorithm.HMAC256(properties.jwtSecret());
        this.verifier = JWT.require(algorithm).withIssuer(properties.jwtIssuer()).build();
    }

    public String issue(OperatorUser user) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(properties.jwtIssuer())
                .withSubject(user.email())
                .withClaim(CLAIM_USER_ID, user.id())
                .withClaim(CLAIM_TENANT, user.tenantId().value())
                .withClaim(CLAIM_ROLE, user.role().name())
                .withIssuedAt(now)
                .withExpiresAt(now.plusMillis(properties.tokenExpirationMs()))
                .sign(algorithm);
    }

    public long expiresInSeconds() {
        return properties.tokenExpirationMs() / 1000;
    }

    /** Lanza JWTVerificationException si la firma, el emisor o la vigencia no cuadran. */
    public AuthenticatedOperator verify(String token) {
        DecodedJWT decoded = verifier.verify(token);
        String tenant = decoded.getClaim(CLAIM_TENANT).asString();
        String role = decoded.getClaim(CLAIM_ROLE).asString();
        String userId = decoded.getClaim(CLAIM_USER_ID).asString();
        if (tenant == null || role == null || userId == null) {
            throw new IllegalArgumentException("El token no transporta tenant_id, role y uid.");
        }
        return new AuthenticatedOperator(userId, decoded.getSubject(), TenantId.of(tenant), Role.valueOf(role));
    }
}
