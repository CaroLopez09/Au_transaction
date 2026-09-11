package com.example.autransactional.application.reference;

import com.example.autransactional.infrastructure.kira.KiraApiClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReferenceCatalogServiceTest {

    private final KiraApiClient kira = mock(KiraApiClient.class);
    private final ReferenceCatalogService service = new ReferenceCatalogService(kira);

    @Test
    void elCatalogoDePaisesSeLeeUnaVezYSeSirveDeCache() {
        when(kira.listCountries()).thenReturn(new ObjectMapper().readTree("""
                { "count": 1, "data": [ { "name": "Colombia", "alpha3": "COL",
                  "postal_code_format": "^[0-9]{6}$",
                  "subdivisions": [ { "name": "Antioquia", "code": "ANT" } ] } ] }
                """));

        List<CountryView> primera = service.countries();
        service.countries();

        assertEquals("COL", primera.getFirst().alpha3());
        assertEquals("ANT", primera.getFirst().subdivisions().getFirst().code());
        verify(kira, times(1)).listCountries();
    }

    @Test
    void unFalloNoSeCachea() {
        when(kira.listCountries()).thenThrow(new IllegalStateException("caido"))
                .thenReturn(new ObjectMapper().readTree("{\"data\":[]}"));

        assertThrows(IllegalStateException.class, service::countries);
        assertTrue(service.countries().isEmpty());
    }
}
