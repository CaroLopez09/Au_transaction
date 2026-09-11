package com.example.autransactional.infrastructure.kira;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Los RFIs solo existen en 2026-06-01 y la cuenta integra con 2026-04-14. Si la cabecera
 * por peticion no se sobrescribe, las rutas de RFI no se encuentran y la bandeja queda vacia
 * sin ningun error visible.
 */
class KiraApiClientVersionTest {

    private MockRestServiceServer server;
    private KiraApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        KiraProperties properties = new KiraProperties("https://kira.test", "key", "client", "pw",
                "2026-04-14", "secret", 3600, 300, 5000, 30000, "slovak_savings_bank", true);
        KiraCredentialManager credentials = mock(KiraCredentialManager.class);
        when(credentials.getAccessToken()).thenReturn("token");
        ObjectMapper mapper = new ObjectMapper();

        client = new KiraApiClient(builder.build(), credentials, properties, new KiraErrorParser(mapper), mapper);
    }

    @Test
    void lasRutasDeRfiViajanConLaVersionQueLasContiene() {
        server.expect(requestTo("/v1/rfis?status=pending"))
                .andExpect(header("X-Api-Version", "2026-06-01"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("/v1/rfis/rfi_1"))
                .andExpect(header("X-Api-Version", "2026-06-01"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("/v1/rfis/rfi_1/items"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("X-Api-Version", "2026-06-01"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.listRfis(Map.of("status", "pending"));
        client.getRfi("rfi_1");
        client.answerRfiItems("rfi_1", Map.of("items", java.util.List.of()));

        server.verify();
    }

    @Test
    void elRestoDeRutasSigueConLaVersionConfigurada() {
        server.expect(requestTo("/v1/payouts/po_1"))
                .andExpect(header("X-Api-Version", "2026-04-14"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getPayout("po_1");

        server.verify();
    }
}
