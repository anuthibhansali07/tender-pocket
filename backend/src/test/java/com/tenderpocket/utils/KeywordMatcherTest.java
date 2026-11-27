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
    void aLongerWordMatchesOnlyBehindAKnownPrefix() {
        assertTrue(KeywordMatcher.matches("Chemistry Analyser", "Biochemistry Analyzer (Semi-Automated)"));
        assertTrue(KeywordMatcher.matches("Dialysis Machine", "Hemodialysis Machine with SLED and HDF"));
        assertTrue(KeywordMatcher.matches("Dialysis Machine", "Haemodialysis Machine"));
        // "extraction" ends in "traction", but "ex" is not a prefix that keeps the meaning.
        assertFalse(KeywordMatcher.matches("Traction Machine", "Banana Fibre Extraction Machine and tools"));
        assertFalse(KeywordMatcher.matches("Pressure Monitor", "Tyre Overpressure Monitor"));
    }

    @Test
    void keywordWordsSitNextToEachOtherWithAtMostOneWordBetween() {
        assertTrue(KeywordMatcher.matches("Analytical Balance", "Micro/Semi-Micro/Analytical weighing balance"));
        assertTrue(KeywordMatcher.matches("Syringe Pump", "Syringe Infusion Pump"));
        assertTrue(KeywordMatcher.matches("Electro Surgical Unit", "Pedestal Lights,Electro surgical Cautery Unit"));
        assertTrue(KeywordMatcher.matches("Emergency Trolley", "Emergency Recovery Trolley (V2)"));
        assertTrue(KeywordMatcher.matches("ICU Bed", "Paediatric Patient Bed for ICU and HDU"));
        assertTrue(KeywordMatcher.matches("Horizontal Autoclave", "M1480902060: Autoclave Horizontal Big Size"));
        // The words are all there, but too far apart to be the product.
        assertFalse(KeywordMatcher.matches("Wheel Chair", "Mid-back Revolving Chair Adjustable Height with Wheels"));
        assertFalse(KeywordMatcher.matches("Laminar Cabinet", "HEPA Filter for Esco Laminar Air Flow Cabinet"));
        assertFalse(KeywordMatcher.matches("Ultrasound Machine", "Ultrasound Combination Therapy Machine"));
    }

    @Test
    void aKeywordThatRepeatsAWordNeedsItOnlyOnce() {
        assertTrue(KeywordMatcher.matches("Particle Board Box bed Particle Board", "Particle Board Box Bed"));
        assertFalse(KeywordMatcher.matches("Particle Board Box bed Particle Board", "Particle Board"));
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
