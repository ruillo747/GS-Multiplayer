package com.gsmultiplayer.security;

import java.security.SecureRandom;

/** Room codes and GS identity helpers. No personal data, no secrets at rest. */
public final class Codes {

    // Unambiguous alphabet: no 0/O, 1/I/L.
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Codes() {
    }

    public static String generateGsId() {
        StringBuilder sb = new StringBuilder("GS-");
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    public static String normalizeRoomCode(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    public static boolean isValidRoomCode(String code) {
        return code != null && code.matches("[A-Z0-9]{4,10}");
    }

    /** True when a user-supplied GS ID looks sane; used before sending it to the server. */
    public static boolean isValidGsId(String gsid) {
        return gsid != null && gsid.matches("GS-[A-Z0-9]{4,16}");
    }

    /** Strips control characters and length-caps free-form user input. */
    public static String sanitizeText(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[\\u0000-\\u001f\\u007f]", "").trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
