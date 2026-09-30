package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.demo.util.HangulKeyboard;

class HangulKeyboardTest {

    @Test
    void convertsEnglishKeystrokesToHangul() {
        assertEquals("엘보", HangulKeyboard.toHangul("dpfqh"));
        assertEquals("피비", HangulKeyboard.toHangul("vlql"));
        assertEquals("소켓", HangulKeyboard.toHangul("thzpt"));
        assertEquals("닭", HangulKeyboard.toHangul("ekfr"));
        assertEquals("값이", HangulKeyboard.toHangul("rkqtdl"));
        assertEquals("와이", HangulKeyboard.toHangul("dhkdl"));
        assertEquals("쌍 15", HangulKeyboard.toHangul("Tkd 15"));
        assertEquals("태은플라자", HangulKeyboard.toHangul("xodmsvmffkwk"));
    }

    @Test
    void detectsOnlyEnglishLookingInput() {
        assertTrue(HangulKeyboard.looksLikeMistypedHangul("dpfqh"));
        assertTrue(HangulKeyboard.looksLikeMistypedHangul("pvc 100"));
        assertFalse(HangulKeyboard.looksLikeMistypedHangul("엘보"));
        assertFalse(HangulKeyboard.looksLikeMistypedHangul("15"));
        assertFalse(HangulKeyboard.looksLikeMistypedHangul("pvc엘보"));
    }
}
