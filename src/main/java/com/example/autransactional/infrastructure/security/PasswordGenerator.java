package com.example.autransactional.infrastructure.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Genera contrasenas temporales aleatorias y seguras para: alta de un operador nuevo y reset
 * administrativo. Nunca se usa en el frontend ni queda hardcodeada: SecureRandom + shuffle,
 * 14 caracteres, garantiza al menos una mayuscula, una minuscula, un digito y un simbolo
 * (mismo patron que el generador equivalente de BankVision).
 */
@Component
public class PasswordGenerator {

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL = "@$!%*?&#";
    private static final String ALL = UPPER + LOWER + DIGITS + SPECIAL;
    private static final int LENGTH = 14;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Contrasena aleatoria de 14 caracteres con al menos uno de cada clase. Nunca se loguea. */
    public String generate() {
        StringBuilder sb = new StringBuilder();
        sb.append(randomChar(UPPER));
        sb.append(randomChar(LOWER));
        sb.append(randomChar(DIGITS));
        sb.append(randomChar(SPECIAL));
        for (int i = 4; i < LENGTH; i++) {
            sb.append(randomChar(ALL));
        }

        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    private static char randomChar(String source) {
        return source.charAt(SECURE_RANDOM.nextInt(source.length()));
    }
}
