package com.tenderpocket.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Lets a GeM keyword search pick up where its last successful search left off.
 *
 * GeM lists the newest bids first. Most of what it returns is unrelated and is dropped by the keyword check,
 * so those bids are never saved, and "stop at bids already in the database" almost never triggered: every sync
 * read each keyword back to the start of yesterday. The sync now records when each keyword was last searched
 * to the end, and the next sync stops that keyword at bids published before then.
 */
final class GemSearchProgress {

    /** Name prefix of the rows kept in sync_markers, one per keyword search. */
    static final String PREFIX = "gem-search:";

    /**
     * Part of the fingerprint. Raise it when KeywordMatcher's rule changes, so that bids the old rule dropped
     * are looked at again.
     */
    static final String RULE_VERSION = "2";

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private GemSearchProgress() {}

    /**
     * Identifies the keyword lists and the matching rule. When either changes, the recorded searches no longer
     * apply: a bid dropped under the old list has to be checked against the new one.
     */
    static String fingerprint(List<String> matchKeywords, List<String> exactKeywords) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("rule " + RULE_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
            for (String keyword : matchKeywords) digest.update((keyword + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update("--\n".getBytes(StandardCharsets.UTF_8));
            for (String keyword : exactKeywords) digest.update((keyword + "\n").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String markerName(String fingerprint, String searchText) {
        String name = PREFIX + fingerprint + ":" + searchText.toLowerCase(Locale.ROOT).trim();
        return name.length() > 250 ? name.substring(0, 250) : name;
    }

    /**
     * When a bid started, from GeM's final_start_date_sort. Returns null when it cannot be read; the caller
     * then treats the bid as new. A value with only a date counts as the end of that day, and a time without
     * a zone as India time: both lean towards "newer", so a bid is read again rather than skipped.
     */
    static Instant bidStart(String gemStart) {
        if (gemStart == null || gemStart.isBlank()) return null;
        String value = gemStart.trim();
        try {
            if (value.endsWith("Z")) return Instant.parse(value);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            if (value.contains("T")) {
                return LocalDateTime.parse(value.replace("Z", "")).atZone(INDIA).toInstant();
            }
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(value.substring(0, Math.min(10, value.length())))
                    .plusDays(1).atStartOfDay(INDIA).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * True when the bid was published long enough before the last successful search of this keyword that the
     * search must have returned it then. The overlap covers a bid that GeM listed some time after its start.
     */
    static boolean coveredByLastSearch(Instant bidStart, Instant lastSearch, Duration overlap) {
        return bidStart != null && lastSearch != null && bidStart.isBefore(lastSearch.minus(overlap));
    }
}
