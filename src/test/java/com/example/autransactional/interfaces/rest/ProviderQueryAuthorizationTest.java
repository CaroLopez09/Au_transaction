package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.account.OpenVirtualAccountService;
import com.example.autransactional.application.account.RecordDepositService;
import com.example.autransactional.application.compliance.AnswerRfiService;
import com.example.autransactional.application.tenant.SubmitOnboardingService;
import com.example.autransactional.application.treasury.ExecutePayoutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G-09: las rutas que consultan a Kira gastan cuota del integrador. Solo lectura no las usa, y el
 * enlace de descarga de un documento de RFI es de cumplimiento.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderQueryAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubmitOnboardingService onboarding;
    @MockitoBean
    private OpenVirtualAccountService accounts;
    @MockitoBean
    private RecordDepositService deposits;
    @MockitoBean
    private ExecutePayoutService payouts;
    @MockitoBean
    private AnswerRfiService rfis;

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void soloLecturaNoConsultaAKira() throws Exception {
        mockMvc.perform(post("/api/onboarding/refresh")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/virtual-accounts/va-1/refresh")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/virtual-accounts/va-1/balance")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/virtual-accounts/va-1/deposits/sync")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/payouts/p-1/refresh")).andExpect(status().isForbidden());

        verifyNoInteractions(onboarding, accounts, deposits, payouts);
    }

    @Test
    @WithMockUser(roles = "TREASURY_APPROVER")
    void tesoreriaSiConsultaElEstadoDeSusOperaciones() throws Exception {
        mockMvc.perform(post("/api/virtual-accounts/va-1/balance")).andExpect(status().isOk());

        verify(accounts).refreshBalance(any(), eq("va-1"));
    }

    @Test
    @WithMockUser(roles = "TREASURY_MAKER")
    void elEnlaceDeUnDocumentoDeRfiEsDeCumplimiento() throws Exception {
        mockMvc.perform(get("/api/rfis/r-1/items/i-1/documents/d-1/link")).andExpect(status().isForbidden());

        verifyNoInteractions(rfis);
    }
}
