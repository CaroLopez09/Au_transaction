package com.example.autransactional;

import org.springframework.boot.SpringApplication;
import com.example.autransactional.application.treasury.PayoutApprovalPolicy;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.autransactional.infrastructure.biometry.BiometryProperties;
import com.example.autransactional.infrastructure.email.EmailProperties;
import com.example.autransactional.application.auth.IdentityVerificationProperties;

@SpringBootApplication
@EnableConfigurationProperties({PayoutApprovalPolicy.class, BiometryProperties.class, IdentityVerificationProperties.class,
        EmailProperties.class})
public class AuTransactionalApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuTransactionalApplication.class, args);
    }

}
