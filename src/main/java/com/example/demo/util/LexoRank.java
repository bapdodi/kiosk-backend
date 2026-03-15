package com.example.demo.util;

public class LexoRank {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final char MIN_CHAR = ALPHABET.charAt(0);
    private static final char MAX_CHAR = ALPHABET.charAt(ALPHABET.length() - 1);
    private static final String INITIAL_RANK = "80000000";

    public static String getInitial() {
        return INITIAL_RANK;
    }

    public static String between(String prev, String next) {
        if (prev == null || prev.isEmpty()) prev = String.valueOf(MIN_CHAR);
        if (next == null || next.isEmpty()) next = String.valueOf(MAX_CHAR).repeat(prev.length() + 1);

        int maxLength = Math.max(prev.length(), next.length());
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < maxLength + 1; i++) {
            char p = i < prev.length() ? prev.charAt(i) : MIN_CHAR;
            char n = i < next.length() ? next.charAt(i) : MAX_CHAR;

            if (p == n) {
                result.append(p);
                continue;
            }

            int pIdx = ALPHABET.indexOf(p);
            int nIdx = ALPHABET.indexOf(n);

            if (nIdx - pIdx > 1) {
                result.append(ALPHABET.charAt(pIdx + (nIdx - pIdx) / 2));
                break;
            } else {
                result.append(p);
                if (i == maxLength) {
                    result.append(ALPHABET.charAt(ALPHABET.length() / 2));
                }
            }
        }
        return result.toString();
    }
}
