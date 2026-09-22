package com.example.autransactional.infrastructure.kira;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class KiraErrorParserTest {

    private final KiraErrorParser parser = new KiraErrorParser(new ObjectMapper());

    @Test
    void extraeElCampoQueFallaCuandoErrorEsPlanoYDetailsEsArreglo() {
        String body = """
                {"error":"Invalid request data","details":[
                  {"path":"account.bank_address.country","message":"String must contain exactly 2 character(s)","code":"too_small"}
                ]}
                """;

        KiraApiException e = parser.parse(400, body);

        assertThat(e.getMessage()).contains("account.bank_address.country");
        assertThat(e.getMessage()).contains("String must contain exactly 2 character(s)");
    }

    @Test
    void extraeCodeYMessageCuandoErrorEsObjetoAnidado() {
        String body = """
                {"error":{"code":"VALIDATION_ERROR","message":"Idempotency key is required","details":{}}}
                """;

        KiraApiException e = parser.parse(400, body);

        assertThat(e.getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(e.getMessage()).isEqualTo("Idempotency key is required");
    }

    @Test
    void mantieneElMensajeGenericoSiNoHayDetailsUtiles() {
        String body = """
                {"error":"Invalid request data","details":{}}
                """;

        KiraApiException e = parser.parse(400, body);

        assertThat(e.getMessage()).isEqualTo("Invalid request data");
    }

    @Test
    void combinaVariosErroresDeCampoEnUnSoloMensaje() {
        String body = """
                {"error":"Invalid request data","details":[
                  {"path":"a","message":"m1"},
                  {"path":"b","message":"m2"}
                ]}
                """;

        KiraApiException e = parser.parse(400, body);

        assertThat(e.getMessage()).contains("a: m1").contains("b: m2");
    }

    @Test
    void seDegradaAlTextoCrudoConCuerpoNoJson() {
        KiraApiException e = parser.parse(500, "boom");

        assertThat(e.getMessage()).isEqualTo("boom");
    }
}
