package com.example.demo.util;

import java.util.Map;

/**
 * 한영키를 누르지 않고 영문 상태로 친 글자를 두벌식 한글로 되돌린다. ("dpfqh" → "엘보")
 * 검색어가 영문 자판 그대로 들어와도 품목/거래처가 뜨도록 하는 용도다.
 */
public final class HangulKeyboard {

    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";

    private static final String KEYS_LOWER = "rsefaqtdwczxvgkoijpuhynbml";
    private static final String JAMO_LOWER = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎㅏㅐㅑㅓㅔㅕㅗㅛㅜㅠㅡㅣ";
    // Shift 를 누르면 모양이 바뀌는 글자만 따로 둔다. 나머지는 소문자와 같다.
    private static final Map<Character, String> SHIFTED = Map.of(
            'R', "ㄲ", 'E', "ㄸ", 'Q', "ㅃ", 'T', "ㅆ", 'W', "ㅉ", 'O', "ㅒ", 'P', "ㅖ");

    private static final Map<String, String> COMPOUND_VOWEL = Map.of(
            "ㅗㅏ", "ㅘ", "ㅗㅐ", "ㅙ", "ㅗㅣ", "ㅚ", "ㅜㅓ", "ㅝ", "ㅜㅔ", "ㅞ", "ㅜㅣ", "ㅟ", "ㅡㅣ", "ㅢ");
    private static final Map<String, String> COMPOUND_FINAL = Map.ofEntries(
            Map.entry("ㄱㅅ", "ㄳ"), Map.entry("ㄴㅈ", "ㄵ"), Map.entry("ㄴㅎ", "ㄶ"), Map.entry("ㄹㄱ", "ㄺ"),
            Map.entry("ㄹㅁ", "ㄻ"), Map.entry("ㄹㅂ", "ㄼ"), Map.entry("ㄹㅅ", "ㄽ"), Map.entry("ㄹㅌ", "ㄾ"),
            Map.entry("ㄹㅍ", "ㄿ"), Map.entry("ㄹㅎ", "ㅀ"), Map.entry("ㅂㅅ", "ㅄ"));

    private HangulKeyboard() {}

    /** 영문 자판으로 친 것 같은 검색어(영문자 포함, 한글 없음)인가. */
    public static boolean looksLikeMistypedHangul(String s) {
        if (s == null) return false;
        boolean letter = false;
        for (char c : s.toCharArray()) {
            if (c >= 0x1100) return false; // 이미 한글 등이 섞여 있으면 손대지 않는다.
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) letter = true;
        }
        return letter;
    }

    /** 영문 자판 입력을 한글로 변환한다. 자판에 없는 글자(숫자, 공백 등)는 그대로 둔다. */
    public static String toHangul(String input) {
        StringBuilder out = new StringBuilder();
        String cho = null, jung = null, jong = null;
        for (char c : input.toCharArray()) {
            String j = jamoOf(c);
            if (j == null) {
                flush(out, cho, jung, jong);
                cho = jung = jong = null;
                out.append(c);
                continue;
            }
            boolean vowel = JUNG.contains(j);
            if (!vowel) {
                if (cho == null) {
                    cho = j;
                } else if (jung == null) {
                    flush(out, cho, null, null);
                    cho = j;
                } else if (jong == null) {
                    if (JONG.contains(j)) jong = j;
                    else { flush(out, cho, jung, null); cho = j; jung = null; }
                } else {
                    String compound = COMPOUND_FINAL.get(jong + j);
                    if (compound != null) jong = compound;
                    else { flush(out, cho, jung, jong); cho = j; jung = jong = null; }
                }
                if (jung == null && jong != null) jong = null; // 방어: 모음 없는 받침은 없다
            } else if (cho == null) {
                out.append(j);
            } else if (jung == null) {
                jung = j;
            } else if (jong == null) {
                String compound = COMPOUND_VOWEL.get(jung + j);
                if (compound != null) {
                    jung = compound;
                } else {
                    flush(out, cho, jung, null);
                    out.append(j);
                    cho = jung = null;
                }
            } else {
                // 받침이 다음 글자의 초성으로 넘어간다. 겹받침이면 뒤쪽만 넘어간다.
                String moved = jong;
                String stay = null;
                if (jong.length() == 1 && !JONG.contains(jong)) moved = jong;
                for (Map.Entry<String, String> e : COMPOUND_FINAL.entrySet()) {
                    if (e.getValue().equals(jong)) {
                        stay = e.getKey().substring(0, 1);
                        moved = e.getKey().substring(1);
                    }
                }
                flush(out, cho, jung, stay);
                cho = moved;
                jung = j;
                jong = null;
            }
        }
        flush(out, cho, jung, jong);
        return out.toString();
    }

    private static String jamoOf(char c) {
        String shifted = SHIFTED.get(c);
        if (shifted != null) return shifted;
        int i = KEYS_LOWER.indexOf(Character.toLowerCase(c));
        return i < 0 || !Character.isLetter(c) ? null : String.valueOf(JAMO_LOWER.charAt(i));
    }

    private static void flush(StringBuilder out, String cho, String jung, String jong) {
        if (cho == null && jung == null) return;
        if (cho == null || jung == null) {
            out.append(cho == null ? jung : cho);
            if (jong != null) out.append(jong);
            return;
        }
        int ci = CHO.indexOf(cho), ji = JUNG.indexOf(jung), ki = jong == null ? 0 : JONG.indexOf(jong);
        if (ci < 0 || ji < 0 || ki < 0) {
            out.append(cho).append(jung);
            if (jong != null) out.append(jong);
            return;
        }
        out.append((char) (0xAC00 + (ci * 21 + ji) * 28 + ki));
    }
}
