package com.example.autransactional;

import org.springframework.boot.SpringApplication;
import com.example.autransactional.application.treasury.PayoutApprovalPolicy;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PayoutApprovalPolicy.class)
public class AuTransactionalApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuTransactionalApplication.class, args);
    }

}
