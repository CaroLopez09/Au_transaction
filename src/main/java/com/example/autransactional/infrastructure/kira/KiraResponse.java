package com.example.autransactional.infrastructure.kira;

import tools.jackson.databind.JsonNode;

/**
 * Respuesta de Kira con su codigo de estado.
 *
 * Hace falta para POST /v1/recipients: un 202 significa "ya existia, te devuelvo el
 * registro existente" y es un exito, no un error. Sin el codigo no hay forma de
 * distinguirlo de un alta nueva, y el portal debe decir "destino ya registrado".
 */
public record KiraResponse(int status, JsonNode body) {

    public boolean alreadyExisted() {
        return status == 202;
    }

    public JsonNode data() {
        return body.has("data") ? body.get("data") : body;
    }
}
