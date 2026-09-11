package com.example.autransactional.application.compliance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public final class RfiCommands {

    private RfiCommands() {
    }

    /**
     * Respuesta a uno o varios items de texto. Responder un subconjunto es valido.
     *
     * Solo items de tipo 'identifier': un item 'document' se responde subiendo archivos y
     * nunca lleva answer_value, asi que aqui se rechaza antes de llamar a Kira.
     */
    public record AnswerItems(@NotEmpty @Valid List<ItemAnswer> items) {
    }

    public record ItemAnswer(@NotBlank String itemId, @NotBlank String answerValue) {
    }
}
