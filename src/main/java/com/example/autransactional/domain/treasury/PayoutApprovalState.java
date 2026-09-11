package com.example.autransactional.domain.treasury;

/** Estado del control interno maker-checker. Vive solo en el BFF; Kira no lo conoce. */
public enum PayoutApprovalState {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    SUBMITTED
}
