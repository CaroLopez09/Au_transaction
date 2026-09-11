package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.treasury.SupportingDocument;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class PayoutCommands {

    private PayoutCommands() {
    }

    public record CreatePayout(
            @NotBlank String virtualAccountId,
            @NotBlank String recipientId,
            String kiraUserId,
            @NotNull @DecimalMin(value = "0.00000001") BigDecimal amount,
            @NotBlank String currency,
            String quotationId) {
    }

    /**
     * Datos que solo se conocen al autorizar.
     *
     * memo es obligatorio para WIRE en algunos bancos corresponsales y viaja en extra_info;
     * los documentos de soporte van como data URI base64, maximo dos y 3 MB cada uno.
     */
    public record ApprovePayout(
            String comment,
            String natureOfPayment,
            @Size(max = 255) String memo,
            @Size(max = SupportingDocument.MAX_DOCUMENTS) List<SupportingDocument> documents) {

        public ApprovePayout {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }

    public record RejectPayout(@NotBlank String reason) {
    }
}
