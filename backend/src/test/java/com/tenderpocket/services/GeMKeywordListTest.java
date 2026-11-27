package com.tenderpocket.services;

import com.tenderpocket.utils.KeywordMatcher;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The GeM keyword list after the approved changes, checked against the bids the old list missed. */
class GeMKeywordListTest {

    private static final List<String> KEYWORDS = GeMScraperService.RAW_KEYWORDS;

    @Test
    void listHasTheApprovedKeywords() {
        assertEquals(145, KEYWORDS.size());
        assertEquals(145, new HashSet<>(KEYWORDS).size(), "no keyword is listed twice");
        // Keywords containing " / " made GeM's search fail and were replaced by plain terms.
        assertTrue(KEYWORDS.stream().noneMatch(k -> k.contains("/")));
        assertTrue(KEYWORDS.containsAll(List.of("Freezer", "Deep Freezer", "Insulated Truck", "Insulated Vehicle",
                "Insulated Van", "Refrigerated Van", "Refrigerated Truck", "Refrigerated Vehicle", "Walk In Cooler",
                "Walk In Freezer", "Cold Room")));
        assertTrue(KEYWORDS.containsAll(List.of("Multipara Monitor", "Cardiac Monitor", "Centrifuge", "Autoclave",
                "Delivery Bed", "Wheelchair", "CO2 Incubator", "Rotary Shaker", "Vaccine Carrier", "Biosafety Cabinet")));
    }

    @Test
    void everyMissedGemBidNowMatches() {
        List<String> missedItems = List.of(
                "Refrigerated Centrifuge For General And Research Purpose", "Rotary Shaker", "Modular Cold Rooms",
                "Deep Freezer (V3) ISI Marked to IS 7872", "Foldable Wheelchair (Adult)", "Medical Centrifuge",
                "CO2 Incubator", "Vertical Autoclave", "Ultra Low Temperature Laboratory Deep Freezer (up to -86°C Degree)",
                "High End Multipara Monitor (V3)", "Laboratory Non Refrigerated Centrifuge (V2)", "Active vaccine carrier",
                "Cardiac Monitor (5 Para)",
                "E cycle,Deep Freezer 500 Ltr,LED Display System with accessories and installation,Washing Machine 8",
                "Biosafety Cabinet (V2)", "Walk in Cooler", "Laboratory Deep Freezer (Upto -20°C Degree)",
                "High End Multipara Monitor (V3),Electrocardiography (ECG) Machine (V2)", "Multipara Monitor - Low End (V2)",
                "ULTRA-LOW TEMPERATURE LABORATORY DEEP FREEZER (-80 c)", "Operating Table (V2)",
                "Fully Automatic Biochemistry Analyzer", "Fully Automatic Biochemistry Analyzer (V2)", "Delivery Bed (V2)",
                "M1480902060: Autoclave Horizontal Big Size");
        for (String item : missedItems) {
            assertNotNull(KeywordMatcher.firstMatch(KEYWORDS, item), "no keyword matches: " + item);
        }
    }

    @Test
    void unrelatedItemsStillDoNotMatch() {
        for (String item : List.of("Entry and Mid Level Desktop Computer", "Roller Blind (V2)", "E-cart for Goods",
                "Behind the Ear Hearing Aid (Digital)", "Ventilation Fan", "Centrifugal Pump", "Truck Tyres",
                "Surgical Face Masks Conforming to IS 16289")) {
            assertNull(KeywordMatcher.firstMatch(KEYWORDS, item), "should not match: " + item);
        }
    }
}
