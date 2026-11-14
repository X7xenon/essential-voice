package com.ishaan.essentialvoice.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishConverterTest {

    @Test
    fun testEnglishRemainsUntouched() {
        val english = "Hello world, this is a test of voice recognition."
        assertFalse(HinglishConverter.hasDevanagari(english))
        assertEquals(english, HinglishConverter.process(english))
    }

    @Test
    fun testDevanagariToHinglish() {
        // "नमस्ते आप कैसे हैं" -> "namaste aap kaise hain"
        val hindi = "नमस्ते"
        assertTrue(HinglishConverter.hasDevanagari(hindi))
        val converted = HinglishConverter.process(hindi)
        assertTrue(converted.startsWith("namast"))
    }

    @Test
    fun testBhaiKaiseHo() {
        val hindi = "भाई कैसे हो"
        val converted = HinglishConverter.process(hindi)
        assertEquals("bhai kaise ho", converted.lowercase())
    }

    @Test
    fun testMixedEnglishHindi() {
        val mixed = "Meeting कल होगी"
        val converted = HinglishConverter.process(mixed)
        assertTrue(converted.contains("Meeting"))
        assertTrue(converted.contains("kal"))
    }

    @Test
    fun testUrduToHinglish() {
        val urdu = "ہا بھائی کیا حال چال؟"
        assertTrue(HinglishConverter.hasUrdu(urdu))
        val converted = HinglishConverter.process(urdu)
        assertEquals("haan bhai kya haal chaal?", converted)
    }

    @Test
    fun testUrduWithArabicVariantsAndTashkeel() {
        // Arabic Kaf (\u0643) and Tashkeel/Harakaat
        val urduVariant = "\u0643\u0650\u064A\u0627 \u062D\u0627\u0644 \u06C1\u06D2" // كِیا حال ہے
        val converted = HinglishConverter.process(urduVariant)
        assertEquals("kya haal hai", converted.lowercase())
    }

    @Test
    fun testHindiSchwaDeletion() {
        val hindi = "काम करना"
        val converted = HinglishConverter.process(hindi)
        assertEquals("kaam karna", converted.lowercase())
    }

    @Test
    fun testZeroNonLatinScriptRemains() {
        val samples = listOf(
            "Hello world",
            "नमस्ते दोस्त कैसे हो",
            "بھائی ٹیسٹ کال کرو",
            "Meeting 4 बजे होगी"
        )
        for (sample in samples) {
            val result = HinglishConverter.process(sample)
            assertFalse("Found Devanagari in: $result", HinglishConverter.hasDevanagari(result))
            assertFalse("Found Urdu in: $result", HinglishConverter.hasUrdu(result))
        }
    }
}
