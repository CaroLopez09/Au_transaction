package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.tenant.ManageOperatorsService;
import com.example.autransactional.application.tenant.OperatorCommands;
import com.example.autransactional.application.tenant.OperatorView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** G-13: quien puede administrar operadores, y F7: sin sesion la respuesta es 401 con cuerpo. */
@SpringBootTest
@AutoConfigureMockMvc
class OperatorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ManageOperatorsService operators;

    private static final String ALTA = """
            {"email":"ana@juriscop.test","firstName":"Ana","lastName":"Gomez",
             "password":"contrasena-larga","role":"TREASURY_MAKER"}""";

    private static OperatorView vista() {
        return new OperatorView("u-1", "ana@juriscop.test", "Ana", "Gomez", "Ana Gomez",
                "TREASURY_MAKER", "Operador", "ACTIVE", true, false);
    }

    // --- F7: falta de sesion ---

    @Test
    void sinCabeceraDeAutorizacionResponde401ConCuerpo() throws Exception {
        mockMvc.perform(get("/api/operators"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("Token de autorizacion ausente o invalido."));

        verifyNoInteractions(operators);
    }

    @Test
    void laFaltaDeSesionNuncaEsUn403Vacio() throws Exception {
        mockMvc.perform(post("/api/operators").contentType(MediaType.APPLICATION_JSON).content(ALTA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));

        mockMvc.perform(delete("/api/operators/u-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    /** Un rol sin permiso sigue siendo 403 con codigo: el portal los distingue por el codigo. */
    @Test
    @WithMockUser(roles = "READ_ONLY")
    void unRolSinPermisoSigueSiendo403ConCodigo() throws Exception {
        mockMvc.perform(get("/api/operators"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        verifyNoInteractions(operators);
    }

    // --- G-13: permisos de la administracion de operadores ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void elAdministradorListaCreaYDesactiva() throws Exception {
        when(operators.list(any())).thenReturn(java.util.List.of(vista()));
        when(operators.create(any(), any())).thenReturn(vista());
        when(operators.suspend(any(), eq("u-1"))).thenReturn(vista());

        mockMvc.perform(get("/api/operators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("TREASURY_MAKER"));

        mockMvc.perform(post("/api/operators").contentType(MediaType.APPLICATION_JSON).content(ALTA))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/operators/u-1")).andExpect(status().isOk());

        verify(operators).list(any());
        verify(operators).create(any(), any());
        verify(operators).suspend(any(), eq("u-1"));
    }

    @Test
    @WithMockUser(roles = "COMPLIANCE_INTERNAL")
    void cumplimientoLosConsultaPeroNoLosAdministra() throws Exception {
        when(operators.list(any())).thenReturn(java.util.List.of(vista()));

        mockMvc.perform(get("/api/operators")).andExpect(status().isOk());
        mockMvc.perform(post("/api/operators").contentType(MediaType.APPLICATION_JSON).content(ALTA))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/operators/u-1")).andExpect(status().isForbidden());

        verify(operators).list(any());
        verify(operators, org.mockito.Mockito.never()).create(any(), any());
        verify(operators, org.mockito.Mockito.never()).suspend(any(), any());
    }

    @Test
    @WithMockUser(roles = "TREASURY_MAKER")
    void tesoreriaNoAdministraOperadores() throws Exception {
        mockMvc.perform(get("/api/operators")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/operators").contentType(MediaType.APPLICATION_JSON).content(ALTA))
                .andExpect(status().isForbidden());

        verifyNoInteractions(operators);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unAltaSinCorreoValidoNoLlegaAlServicio() throws Exception {
        String invalido = """
                {"email":"no-es-un-correo","firstName":"Ana","lastName":"Gomez",
                 "password":"contrasena-larga","role":"TREASURY_MAKER"}""";

        mockMvc.perform(post("/api/operators").contentType(MediaType.APPLICATION_JSON).content(invalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));

        verifyNoInteractions(operators);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unaContrasenaCortaNoLlegaAlServicio() throws Exception {
        String corta = """
                {"email":"ana@juriscop.test","firstName":"Ana","lastName":"Gomez",
                 "password":"corta","role":"TREASURY_MAKER"}""";

        mockMvc.perform(post("/api/operators").contentType(MediaType.APPLICATION_JSON).content(corta))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));

        verifyNoInteractions(operators);
    }

    /** El comando no tiene campo de empresa: el aislamiento no depende de que el portal colabore. */
    @Test
    void elComandoDeAltaNoAdmiteEmpresa() {
        for (var componente : OperatorCommands.CreateOperator.class.getRecordComponents()) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    componente.getName().toLowerCase().contains("tenant"),
                    "CreateOperator no debe aceptar la empresa: sale de la sesion");
        }
    }
}
