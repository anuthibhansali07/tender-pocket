package com.tenderpocket.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenderKeywordsTest {

    @Test
    void keywordListIsSharedWithGem() {
        assertTrue(TenderKeywords.RAW_KEYWORDS.size() > 100);
        assertTrue(TenderKeywords.RAW_KEYWORDS.contains("Hospital Bed"));
        assertEquals(2, TenderKeywords.countMatchingKeywords("ICU Bed and Patient Monitor", TenderKeywords.RAW_KEYWORDS));
    }

    @Test
    void wholeWordMatchingAvoidsPartialWords() {
        assertEquals("Fowler Bed", TenderKeywords.firstWholeWordKeyword("Supply of Semi fowler bed x 20",
                List.of("Fowler Bed")));
        assertNull(TenderKeywords.firstWholeWordKeyword("Cappuccino machines", List.of("CPAP")));
        assertNull(TenderKeywords.firstWholeWordKeyword("Bipapers supply", List.of("BIPAP")));
        assertEquals("BIPAP", TenderKeywords.firstWholeWordKeyword("Purchase of BiPAP (2 nos)", List.of("BIPAP")));
        assertEquals("X-Ray Machine", TenderKeywords.firstWholeWordKeyword("Digital X-Ray Machine 500mA",
                List.of("X-Ray Machine")));
    }

    @Test
    void genericGoods() {
        assertTrue(TenderKeywords.mentionsGenericGoods("Supply of Equipment and Instrument for DH"));
        assertTrue(TenderKeywords.mentionsGenericGoods("Hospital furniture items"));
        assertFalse(TenderKeywords.mentionsGenericGoods("Purchase of medicines and consumables"));
    }

    @Test
    void verticals() {
        assertEquals("Medical Equipment & Furniture", TenderKeywords.verticalFor("ICU Bed"));
        assertEquals("Deep Freezer", TenderKeywords.verticalFor("Deep Freezer -86C"));
        assertEquals("HVAC", TenderKeywords.verticalFor("Chiller plant AMC"));
        assertEquals("Others", TenderKeywords.verticalFor(null));
    }
}
