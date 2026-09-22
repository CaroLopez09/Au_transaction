package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.tenant.SubmitOnboardingService;
import com.example.autransactional.application.tenant.SyncUbosService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El enganche multipart de los documentos KYB.
 *
 * Existe por un fallo real: con `types` declarado como @RequestPart, una parte de texto sin
 * content-type llega como application/octet-stream, Spring no encuentra convertidor y el
 * endpoint respondia 500 antes de ejecutar una sola linea del caso de uso. Las pruebas de
 * servicio no lo veian porque no pasan por la capa web.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KybDocumentUploadTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubmitOnboardingService onboarding;

    @MockitoBean
    private SyncUbosService ubos;

    private MockMultipartFile archivo() {
        return new MockMultipartFile("files", "id.png", "image/png",
                "PNG".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void laParteTypesSeResuelveSinReventarLaCapaWeb() throws Exception {
        mockMvc.perform(multipart("/api/onboarding/documents")
                        .file(archivo())
                        .param("types", "front")
                        .param("informationType", "passport")
                        .param("issuingCountry", "COL"))
                // Llega al caso de uso (mockeado): lo que importa es que no es 500.
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unTypePorCadaFileOSeRechazaAntesDeLlamarAKira() throws Exception {
        mockMvc.perform(multipart("/api/onboarding/documents")
                        .file(archivo())
                        .param("types", "front", "back")
                        .param("informationType", "passport")
                        .param("issuingCountry", "COL"))
                .andExpect(status().isUnprocessableContent());

        verifyNoInteractions(onboarding);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void elMismoEngancheValeParaLosDocumentosDeUnBeneficiario() throws Exception {
        mockMvc.perform(multipart("/api/ubos/ubo-1/documents")
                        .file(archivo())
                        .param("types", "selfie")
                        .param("informationType", "passport")
                        .param("issuingCountry", "COL"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "TREASURY_APPROVER")
    void unRolQueNoEsAdminNoSubeDocumentosDeCumplimiento() throws Exception {
        mockMvc.perform(multipart("/api/onboarding/documents")
                        .file(archivo())
                        .param("types", "front")
                        .param("informationType", "passport")
                        .param("issuingCountry", "COL"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(onboarding, ubos);
    }
}
