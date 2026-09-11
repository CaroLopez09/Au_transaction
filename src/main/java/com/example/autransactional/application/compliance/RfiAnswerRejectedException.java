package com.example.autransactional.application.compliance;

import com.example.autransactional.domain.shared.DomainException;

import java.util.Map;

/**
 * Una o varias respuestas de un RFI no son validas. El error va POR item_id: el PATCH de
 * Kira es all-or-nothing, asi que ningun item se guardo y el portal no debe marcar ninguno
 * como respondido.
 */
public class RfiAnswerRejectedException extends DomainException {

    private final Map<String, String> itemErrors;

    public RfiAnswerRejectedException(String message, Map<String, String> itemErrors) {
        super(message);
        this.itemErrors = Map.copyOf(itemErrors);
    }

    public Map<String, String> itemErrors() {
        return itemErrors;
    }
}
