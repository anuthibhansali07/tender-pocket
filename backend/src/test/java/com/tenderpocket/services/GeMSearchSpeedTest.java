package com.tenderpocket.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tenderpocket.repositories.TenderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;

class GeMSearchSpeedTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("GeM's '404: No data found' is an empty result, not a failure")
    void noDataFoundIsEmpty() {
        assertTrue(GeMScraperService.isNoResults(404, "No data found"));
        assertFalse(GeMScraperService.isNoResults(500, "Something went wrong, please try again after some time"));
        assertFalse(GeMScraperService.isNoResults(404, null));
    }

    @Test
    @DisplayName("Slashes are taken out of the GeM search text, which GeM rejects with code 500")
    void searchTextWithoutSlash() {
        assertEquals("Freezer Deep Freezer", GeMScraperService.searchTextFor("Freezer / Deep Freezer"));
        assertEquals("Walk-In-Freezer Cold Room", GeMScraperService.searchTextFor("Walk-In-Freezer / Cold Room"));
        assertEquals("Boyle's Machine", GeMScraperService.searchTextFor("Boyle's Machine"));
    }

    /** GeM stand-in: 35 unrelated bids from today, 10 per page, newest first. */
    static final class FakeGem extends GeMScraperService {
        final List<Integer> pagesRequested = new ArrayList<>();

        @Override
        JsonNode searchKeywordWithRetry(String keyword, int page) {
            pagesRequested.add(page);
            ArrayNode docs = JSON.createArrayNode();
            String today = LocalDate.now(ZoneId.of("Asia/Kolkata")) + "T10:00:00";
            for (int i = (page - 1) * 10; i < Math.min(35, page * 10); i++) {
                ObjectNode d = docs.addObject();
                d.put("b_id", String.valueOf(9000 + i));
                d.put("b_bid_number", "GEM/2026/B/" + (9000 + i));
                d.put("final_start_date_sort", today);
                d.put("b_category_name", "Interactive Panels with CPU");
                d.put("ba_official_details_deptName", "Some Department");
            }
            return docs;
        }
    }

    @Test
    @DisplayName("A later run stops after two pages of bids already checked, instead of re-reading every page")
    void laterRunStopsOnBidsCheckedEarlier() throws Exception {
        FakeGem gem = new FakeGem();
        TenderRepository repo = Mockito.mock(TenderRepository.class);
        Mockito.when(repo.findFirstByRefNo(anyString())).thenReturn(Optional.empty());
        Mockito.when(repo.findById(anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(gem, "tenderRepository", repo);
        ReflectionTestUtils.setField(gem, "requestPauseMs", 0L);
        ReflectionTestUtils.setField(gem, "maxPagesPerKeyword", 15);
        ReflectionTestUtils.setField(gem, "stopAfterKnown", 20);
        ReflectionTestUtils.setField(gem, "firstPageIndex", 1);
        ReflectionTestUtils.setField(gem, "maxConsecutiveFailedKeywords", 5);

        GeMScraperService.SyncResult first = gem.syncTendersDetailed(false, List.of("wheelchair"));
        assertEquals(0, first.imported);
        assertEquals(List.of(1, 2, 3, 4), gem.pagesRequested, "first run reads every page");

        gem.pagesRequested.clear();
        gem.syncTendersDetailed(false, List.of("wheelchair"));
        assertEquals(List.of(1, 2), gem.pagesRequested, "second run stops after 20 bids it already checked");
    }
}
