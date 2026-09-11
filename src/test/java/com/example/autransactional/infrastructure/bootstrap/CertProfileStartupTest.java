package com.example.autransactional.infrastructure.bootstrap;

import com.example.autransactional.AuTransactionalApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fuera de desarrollo el arranque debe caerse si falta un secreto, en lugar de quedar
 * en pie firmando tokens con una clave de ejemplo o fallando en la primera llamada a Kira.
 */
class CertProfileStartupTest {

    private ConfigurableApplicationContext arrancarCert(String... propiedades) {
        return new SpringApplicationBuilder(AuTransactionalApplication.class)
                .profiles("cert")
                .web(WebApplicationType.SERVLET)
                // Como argumentos, no como properties: asi tienen mas precedencia que
                // el application-cert.yaml, que apunta a la base real via ${DB_URL}.
                .run(argumentos(propiedades,
                        "spring.datasource.url=jdbc:h2:mem:cert-startup;DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.jpa.hibernate.ddl-auto=create-drop",
                        "server.port=0"));
    }

    @Test
    void certNoArrancaSinLosSecretosDeKira() {
        var e = assertThrows(Exception.class, () -> arrancarCert(
                "kira.api-key=", "kira.client-id=", "kira.password=",
                "kira.webhook-secret=", "bff.security.jwt-secret="));

        assertTrue(raiz(e).getMessage().contains("KIRA_API_KEY"), raiz(e).getMessage());
    }

    @Test
    void certArrancaConTodosLosSecretosPresentes() {
        try (var context = arrancarCert(
                "kira.api-key=k", "kira.client-id=c", "kira.password=p",
                "kira.webhook-secret=w",
                "bff.security.jwt-secret=un-secreto-de-al-menos-32-caracteres-largo")) {

            assertTrue(context.isRunning());
            // El sembrador de desarrollo no debe existir fuera del perfil dev.
            assertEquals(0, context.getBeanNamesForType(DevDataSeeder.class).length);
            assertEquals(1, context.getBeanNamesForType(RequiredSecretsValidator.class).length);
        }
    }

    private static Throwable raiz(Throwable e) {
        Throwable actual = e;
        while (actual.getCause() != null) {
            actual = actual.getCause();
        }
        return actual;
    }

    private static String[] argumentos(String[] a, String... b) {
        return java.util.stream.Stream.concat(java.util.Arrays.stream(a), java.util.Arrays.stream(b))
                .map(p -> "--" + p)
                .toArray(String[]::new);
    }
}
