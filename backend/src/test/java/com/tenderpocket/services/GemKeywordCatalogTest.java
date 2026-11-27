package com.tenderpocket.services;

import com.tenderpocket.utils.KeywordMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The built-in keywords plus the client's master list. */
class GemKeywordCatalogTest {

    private final GemKeywordCatalog catalog = GemKeywordCatalog.load(GeMScraperService.RAW_KEYWORDS, false);

    @Test
    void clientListIsAddedToTheBuiltInList() {
        assertEquals(1356, GemKeywordCatalog.readClientList().size());
        assertTrue(catalog.matchKeywords.containsAll(GeMScraperService.RAW_KEYWORDS), "no built-in keyword is dropped");
        assertTrue(catalog.matchKeywords.containsAll(List.of("Blood Bank Refrigerator", "BBR", "Refrigerated Centrifuse",
                "Operating Table", "Biochemistry Analyzer", "Medical Equipment")));
        assertTrue(catalog.matchKeywords.size() > 1400, "about 1,450 keywords once duplicates are removed");
    }

    @Test
    void onlyMainNamesAndGeneralTermsAreSearchedByDefault() {
        assertTrue(catalog.searchKeywords.containsAll(GeMScraperService.RAW_KEYWORDS));
        assertTrue(catalog.searchKeywords.contains("Blood Bank Refrigerator"), "a main product name");
        assertTrue(catalog.searchKeywords.contains("Hospital Equipment"), "a general term");
        assertFalse(catalog.searchKeywords.contains("Blood Bank Fridge"), "an alternative name is not searched");
        assertTrue(catalog.searchKeywords.size() < 560, "searches per sync stay in the hundreds, not 1,400");

        GemKeywordCatalog everything = GemKeywordCatalog.load(GeMScraperService.RAW_KEYWORDS, true);
        assertTrue(everything.searchKeywords.contains("Blood Bank Fridge"));
        assertEquals(catalog.matchKeywords, everything.matchKeywords);
    }

    @Test
    void everyBidTheOldListMissedIsRecognised() {
        for (String item : List.of("Refrigerated Centrifuge For General And Research Purpose", "Rotary Shaker",
                "Modular Cold Rooms", "Deep Freezer (V3) ISI Marked to IS 7872", "Foldable Wheelchair (Adult)",
                "Medical Centrifuge", "CO2 Incubator", "Vertical Autoclave", "High End Multipara Monitor (V3)",
                "Active vaccine carrier", "Cardiac Monitor (5 Para)", "Biosafety Cabinet (V2)", "Walk in Cooler",
                "Operating Table (V2)", "Fully Automatic Biochemistry Analyzer (V2)", "Delivery Bed (V2)",
                "M1480902060: Autoclave Horizontal Big Size", "Multipara Monitor - Low End (V2)")) {
            assertNotNull(KeywordMatcher.firstMatch(catalog.matchKeywords, item), "no keyword matches: " + item);
        }
    }

    @Test
    void shortKeywordsMatchWholeWordsOnly() {
        // "EMS", "UPS" and "LIS" are in the client's list. As plain text they sit inside other words.
        assertNull(KeywordMatcher.firstMatch(catalog.matchKeywords, "Stationery items for office systems"));
        assertNull(KeywordMatcher.firstMatch(catalog.matchKeywords, "Groups of English publishers"));
        assertEquals("UPS", KeywordMatcher.firstMatch(catalog.matchKeywords, "Online UPS (V2)"));
    }
}
