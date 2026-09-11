package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.reference.CountryView;
import com.example.autransactional.application.reference.ReferenceCatalogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "0. Catalogos", description = "Catalogos de referencia de Kira, cacheados en el BFF.")
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    private final ReferenceCatalogService catalog;

    public ReferenceController(ReferenceCatalogService catalog) {
        this.catalog = catalog;
    }

    /** Paises soportados con sus subdivisiones. Cacheado 24 h. */
    @GetMapping("/countries")
    public List<CountryView> countries() {
        return catalog.countries();
    }
}
