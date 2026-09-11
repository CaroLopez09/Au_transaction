package com.example.autransactional.domain.compliance.identity;

/** Puerto de lectura del documento. El reverso es opcional: el pasaporte no lo tiene. */
public interface DocumentOcr {

    DocumentData read(byte[] front, byte[] back, String countryCode, String documentType);
}
