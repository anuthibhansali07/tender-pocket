package com.tenderpocket.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** GeM search session handling, from the live behaviour seen on 2026-11-15. */
class GeMSearchSessionTest {

    @Test
    void searchReplyRotatesTheCsrfCookieAndKeepsTheRest() {
        // After /all-bids: four cookies. Each /all-bids-data reply re-sets csrf_gem_cookie and the F5 cookie.
        String afterPage = GeMScraperService.mergeCookies(null, List.of(
                "csrf_gem_cookie=4b368a66aa; expires=Sun, 15-Nov-2026 20:00:00 GMT; path=/; secure",
                "ci_session=s1; path=/; HttpOnly",
                "GeM=g1; path=/",
                "TS0174a79d=t1; Path=/"));
        assertEquals("csrf_gem_cookie=4b368a66aa; ci_session=s1; GeM=g1; TS0174a79d=t1", afterPage);

        String afterSearch = GeMScraperService.mergeCookies(afterPage, List.of(
                "csrf_gem_cookie=9f00c1d2bb; expires=Sun, 15-Nov-2026 20:00:00 GMT; path=/; secure",
                "TS0174a79d=t2; Path=/"));
        assertEquals("csrf_gem_cookie=9f00c1d2bb; ci_session=s1; GeM=g1; TS0174a79d=t2", afterSearch);
        assertEquals("9f00c1d2bb", GeMScraperService.cookieValue(afterSearch, "csrf_gem_cookie"),
                "the next search must send the rotated token");
    }

    @Test
    void cookieHelpersTolerateMissingInput() {
        assertEquals("", GeMScraperService.mergeCookies(null, null));
        assertEquals("a=1", GeMScraperService.mergeCookies("a=1", List.of()));
        assertNull(GeMScraperService.cookieValue(null, "csrf_gem_cookie"));
        assertNull(GeMScraperService.cookieValue("a=1; b=2", "csrf_gem_cookie"));
        assertEquals("x=y", GeMScraperService.cookieValue("tok=x=y", "tok"), "values may contain '='");
    }

    @Test
    void stopsTheSyncOnlyAfterTheConfiguredRunOfFailedKeywords() {
        assertFalse(GeMScraperService.shouldStopAfterFailures(4, 5));
        assertTrue(GeMScraperService.shouldStopAfterFailures(5, 5));
        assertFalse(GeMScraperService.shouldStopAfterFailures(50, 0), "0 disables the check");
    }
}
