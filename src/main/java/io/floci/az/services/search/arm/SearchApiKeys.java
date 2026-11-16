package io.floci.az.services.search.arm;

import java.security.SecureRandom;

/** Search API keys: 52 random letters and digits, the form Learn's "Connect using API keys" describes. */
final class SearchApiKeys {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 52;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SearchApiKeys() {
    }

    /** A new admin or query key. */
    static String newKey() {
        StringBuilder key = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            key.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return key.toString();
    }
}
