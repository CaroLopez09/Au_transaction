package com.example.autransactional.interfaces.rest;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** G-18: el portal pregunta que permite el entorno en vez de deducirlo de su compilacion. */
@SpringBootTest
@AutoConfigureMockMvc
class CapabilitiesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static UsernamePasswordAuthenticationToken sesion(Role role, String tenant) {
        var operador = new AuthenticatedOperator(tenant + ":usuario", "op@" + tenant + ".test",
                new TenantId(tenant), role);
        return new UsernamePasswordAuthenticationToken(operador, null,
                createAuthorityList(List.of("ROLE_" + role.name()).toArray(String[]::new)));
    }

    @Test
    void sinSesionNoSeExponenLasCapacidades() throws Exception {
        mockMvc.perform(get("/api/capabilities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void cualquierRolDeEmpresaLasLee() throws Exception {
        mockMvc.perform(get("/api/capabilities").with(authentication(sesion(Role.READ_ONLY, "juriscop"))))
                .andExpect(status().isOk())
                // El perfil de pruebas si trae KIRA_*; sin ellas el portal dira "pendiente de configuracion".
                .andExpect(jsonPath("$.providerConfigured").value(true))
                .andExpect(jsonPath("$.sandbox").value(true))
                .andExpect(jsonPath("$.bank").value("jp_morgan"))
                .andExpect(jsonPath("$.providerApiVersion").value("2026-06-01"));
    }

    @Test
    void elOperadorDePlataformaNoTieneUmbralDeEmpresa() throws Exception {
        mockMvc.perform(get("/api/capabilities").with(authentication(sesion(Role.PLATFORM_OPERATOR, "platform"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dualApprovalThreshold").doesNotExist());
    }
}
