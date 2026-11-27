package com.tenderpocket.services;

import com.tenderpocket.utils.KeywordMatcher;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The keywords the GeM sync works with: the list kept in the code plus the client's master list
 * (gem/client-keywords.tsv: every product's main name, its alternative names, and general terms).
 *
 * The client's list holds about 1,350 keywords. Searching GeM once per keyword would take hours per sync, and
 * GeM's search already returns anything that contains one of the words. So by default only the main product
 * names and general terms are searched; the alternative names are used to recognise a bid once it is returned.
 */
public final class GemKeywordCatalog {

    static final String CLIENT_LIST = "gem/client-keywords.tsv";

    /** Sent to GeM's search, one search per keyword. */
    public final List<String> searchKeywords;
    /** A bid is kept when one of these matches its item name. */
    public final List<String> matchKeywords;

    private GemKeywordCatalog(List<String> searchKeywords, List<String> matchKeywords) {
        this.searchKeywords = Collections.unmodifiableList(searchKeywords);
        this.matchKeywords = Collections.unmodifiableList(matchKeywords);
    }

    /** One line of the client's list. */
    record Entry(String type, String keyword) {}

    static List<Entry> readClientList() {
        List<Entry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(CLIENT_LIST).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                if (header) {            // the column names
                    header = false;
                    continue;
                }
                String[] cells = line.split("\t", -1);
                if (cells.length >= 2 && !cells[1].isBlank()) {
                    entries.add(new Entry(cells[0].trim(), cells[1].trim()));
                }
            }
        } catch (Exception e) {
            System.err.println("[GeM Sync] The client's keyword list could not be read (" + e.getMessage()
                    + "); continuing with the built-in keywords only.");
        }
        return entries;
    }

    /**
     * @param builtIn            the keywords kept in the code; always searched and matched, and listed first
     * @param searchAlternatives also search GeM for the client's alternative names (slow: one search each)
     */
    public static GemKeywordCatalog load(List<String> builtIn, boolean searchAlternatives) {
        List<String> search = new ArrayList<>(), match = new ArrayList<>();
        Set<String> searchSeen = new HashSet<>(), matchSeen = new HashSet<>();
        for (String keyword : builtIn) {
            add(keyword, true, search, searchSeen, match, matchSeen);
        }
        for (Entry entry : readClientList()) {
            boolean searched = searchAlternatives || !"Alternative".equalsIgnoreCase(entry.type());
            add(entry.keyword(), searched, search, searchSeen, match, matchSeen);
        }
        return new GemKeywordCatalog(search, match);
    }

    private static void add(String keyword, boolean searched, List<String> search, Set<String> searchSeen,
                            List<String> match, Set<String> matchSeen) {
        if (keyword == null || keyword.trim().length() < 2) return;
        String trimmed = keyword.trim();
        if (matchSeen.add(trimmed.toLowerCase())) match.add(trimmed);
        // Two keywords that are the same once punctuation is removed need only one search.
        if (searched && trimmed.length() >= 3 && searchSeen.add(KeywordMatcher.searchText(trimmed).toLowerCase())) {
            search.add(trimmed);
        }
    }
}
