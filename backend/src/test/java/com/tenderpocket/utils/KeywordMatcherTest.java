package com.tenderpocket.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeywordMatcherTest {

    @Test
    void wordingDifferencesStillMatch() {
        assertTrue(KeywordMatcher.matches("Operation Table", "Operating Table (V2)"));
        assertTrue(KeywordMatcher.matches("Chemistry Analyser", "Fully Automatic Biochemistry Analyzer (V2)"));
        assertTrue(KeywordMatcher.matches("ECG Machine", "High End Multipara Monitor (V3),Electrocardiography (ECG) Machine (V2)"));
        assertTrue(KeywordMatcher.matches("Deep Freezer", "ULTRA-LOW TEMPERATURE LABORATORY DEEP FREEZER (-80 c)"));
        assertTrue(KeywordMatcher.matches("Cold Room", "Modular Cold Rooms"));
        assertTrue(KeywordMatcher.matches("Walk In Cooler", "Walk in Cooler"));
        assertTrue(KeywordMatcher.matches("Fowler Bed", "Patient Bed (Fowler)"));
        assertTrue(KeywordMatcher.matches("Multipara Monitor", "Multipara Monitor - Low End (V2)"));
        assertTrue(KeywordMatcher.matches("Autoclave", "M1480902060: Autoclave Horizontal Big Size"));
        assertTrue(KeywordMatcher.matches("Haematology Analyzer", "CBC Analyzer Hematology"));
    }

    @Test
    void lookAlikeWordsDoNotMatch() {
        assertFalse(KeywordMatcher.matches("Ventilator", "Ventilation Fan"));
        assertFalse(KeywordMatcher.matches("Chiller", "Red Chillies"));
        assertFalse(KeywordMatcher.matches("Electrolyte", "Electrolytic Capacitor"));
        assertFalse(KeywordMatcher.matches("Operation Table", "Operation Cable"));
        assertFalse(KeywordMatcher.matches("Operation Table", "Portable Table Fan"));
        assertFalse(KeywordMatcher.matches("Colorimeter", "Bomb Calorimeter"));
        assertFalse(KeywordMatcher.matches("Refrigerator", "Refrigerated Display Counter"));
        assertFalse(KeywordMatcher.matches("Centrifuge", "Centrifugal Pump"));
        assertFalse(KeywordMatcher.matches("Hospital Chair", "Operator Chair"));
        assertFalse(KeywordMatcher.matches("Modular Multipara Monitor", "High End Multipara Monitor (V3)"));
    }

    @Test
    void wordsMustComeFromTheSameItemAndSitTogether() {
        assertFalse(KeywordMatcher.matches("Hospital Bed", "Bed Sheet, Hospital Curtain"));
        assertTrue(KeywordMatcher.matches("Deep Freezer", "E cycle,Deep Freezer 500 Ltr,LED Display System"));
        assertFalse(KeywordMatcher.matches("Hospital Bed",
                "Hospital waste collection bins with lids and wheels and a spare bed"));
    }

    @Test
    void firstMatchAndNearMiss() {
        List<String> keywords = List.of("Hospital Bed", "Modular Multipara Monitor", "Centrifuge");
        assertEquals("Centrifuge", KeywordMatcher.firstMatch(keywords, "Refrigerated Centrifuge For General And Research Purpose"));
        assertNull(KeywordMatcher.firstMatch(keywords, "Desktop Computer"));
        assertNull(KeywordMatcher.firstMatch(keywords, null));
        assertEquals("Modular Multipara Monitor", KeywordMatcher.nearMiss(keywords, "High End Multipara Monitor (V3)"));
        assertNull(KeywordMatcher.nearMiss(keywords, "Bed Sheet"));
    }

    @Test
    void searchTextDropsPunctuationThatBreaksGemSearch() {
        assertEquals("X Ray Machine", KeywordMatcher.searchText("X-Ray Machine"));
        assertEquals("Boyles Machine", KeywordMatcher.searchText("Boyle's Machine"));
        assertEquals("C ARM Machine", KeywordMatcher.searchText("C-ARM Machine"));
        assertEquals("Hospital Bed", KeywordMatcher.searchText("Hospital Bed"));
    }
}
