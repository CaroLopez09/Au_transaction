package com.example.autransactional.application.reference;

import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Catalogos de referencia de Kira.
 *
 * El de paises es estable (sin cambios desde 2025-01-01) y lo usan todos los formularios de
 * direccion: se cachea 24 h para no gastar una llamada autenticada cada vez que se pinta uno.
 * Un fallo no se cachea: la siguiente peticion vuelve a intentarlo.
 */
@Service
public class ReferenceCatalogService {

    private static final String COUNTRIES = "countries";

    private final KiraApiClient kira;
    private final Cache<String, List<CountryView>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(10)
            .build();

    public ReferenceCatalogService(KiraApiClient kira) {
        this.kira = kira;
    }

    public List<CountryView> countries() {
        return cache.get(COUNTRIES, key -> load());
    }

    private List<CountryView> load() {
        JsonNode response = kira.listCountries();
        JsonNode data = response.isArray() ? response : response.path("data");
        List<CountryView> countries = new ArrayList<>();
        for (JsonNode country : data) {
            List<CountryView.Subdivision> subdivisions = new ArrayList<>();
            for (JsonNode sub : country.path("subdivisions")) {
                subdivisions.add(new CountryView.Subdivision(sub.path("name").asText(null),
                        sub.path("code").asText(null)));
            }
            countries.add(new CountryView(country.path("name").asText(null),
                    country.path("alpha3").asText(null),
                    country.path("postal_code_format").asText(null),
                    List.copyOf(subdivisions)));
        }
        return List.copyOf(countries);
    }
}
