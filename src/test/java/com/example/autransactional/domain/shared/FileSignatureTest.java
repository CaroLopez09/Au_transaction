package com.example.autransactional.domain.shared;

import com.example.autransactional.domain.treasury.SupportingDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FileSignatureTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    private static final byte[] HEIC = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
    /** Cabecera de un ejecutable de Windows. */
    private static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0};

    @Test
    void reconoceLosFormatosQueKiraAcepta() {
        assertEquals("application/pdf", FileSignature.detect("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("image/png", FileSignature.detect(PNG));
        assertEquals("image/jpeg", FileSignature.detect(JPEG));
        assertEquals("image/webp", FileSignature.detect(WEBP));
        assertEquals("image/heic", FileSignature.detect(HEIC));
        assertNull(FileSignature.detect(EXE));
    }

    @Test
    void elTipoDeclaradoTieneQueCoincidirConElContenido() {
        assertTrue(FileSignature.matches("image/png", PNG));
        assertTrue(FileSignature.matches("IMAGE/JPG", JPEG));
        assertFalse(FileSignature.matches("application/pdf", PNG));
        assertFalse(FileSignature.matches("image/png", EXE));
        assertFalse(FileSignature.matches("image/png", new byte[0]));
    }

    @Test
    void elDocumentoDeSoporteDelPagoTambienSeComprueba() {
        assertDoesNotThrow(() -> new SupportingDocument("invoice", "data:application/pdf;base64,JVBERi0xLjQ="));
        // "iVBORw0KGgo=" es la firma PNG: declarada como PDF no pasa.
        assertThrows(DomainException.class,
                () -> new SupportingDocument("invoice", "data:application/pdf;base64,iVBORw0KGgo="));
    }
}
