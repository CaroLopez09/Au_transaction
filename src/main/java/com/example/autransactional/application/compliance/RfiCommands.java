package com.example.autransactional.application.compliance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class RfiCommands {

    private RfiCommands() {
    }

    /**
     * Respuesta a uno o varios items. Responder un subconjunto es valido.
     *
     * Un item 'document' se responde subiendo archivos y nunca lleva answer_value, asi que
     * aqui se rechaza antes de llamar a Kira.
     */
    public record AnswerItems(@NotEmpty @Valid List<ItemAnswer> items) {
    }

    /**
     * answerValue es texto, numero o booleano segun el answer_type del item (text_short,
     * number, boolean, choice, date, identifier...). Kira lo valida contra el answer_spec.
     */
    public record ItemAnswer(@NotBlank String itemId, @NotNull Object answerValue) {
    }

    /** Archivo recibido del portal para un item de tipo documento. */
    public record UploadedFile(String fileName, String contentType, byte[] content) {
    }
}
