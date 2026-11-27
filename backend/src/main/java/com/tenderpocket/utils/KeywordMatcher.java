package com.tenderpocket.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a GeM item name is one of the products named by a keyword.
 *
 * A keyword matches when every one of its words is found in the same item of the bid (a multi-item bid
 * lists its items separated by commas), next to each other and in any order; one other word may sit between
 * them ("Analytical weighing balance" for "Analytical Balance"). Words are compared without regard to
 * capitals, punctuation, GeM's "(V2)" tags, UK/US spelling and plurals. A long word may also differ in its
 * ending ("Operation Table" matches "Operating Table") or carry a known prefix ("Chemistry Analyser" matches
 * "Biochemistry Analyzer", but "Traction" does not match "Extraction"). Short words and one-word keywords
 * must match exactly, apart from the plural, so "Table" never matches "Cable" and "Ventilator" never
 * matches "Ventilation".
 */
public final class KeywordMatcher {

    private KeywordMatcher() {}

    private static final Set<String> STOP_WORDS =
            Set.of("and", "of", "for", "the", "with", "cum", "or", "in", "to", "on", "by", "as", "per", "is");

    private static final Map<String, String> SPELLINGS = Map.of(
            "analyser", "analyzer", "haematology", "hematology", "haemoglobin", "hemoglobin", "colour", "color",
            "theatre", "theater", "paediatric", "pediatric", "litre", "liter", "centre", "center");

    private static final String[] ENDINGS =
            {"ation", "ating", "ions", "ion", "ing", "ers", "ors", "er", "or", "ed", "es", "s"};

    /** A keyword word may be the end of a longer word only behind one of these: "bio" + "chemistry", "hemo" + "dialysis". */
    private static final Set<String> COMPOUND_PREFIXES = Set.of("bio", "hemo", "haemo", "micro", "ultra", "auto",
            "electro", "video", "immuno", "cryo", "neo", "multi", "mini", "semi", "photo", "thermo", "hydro");

    /** How many other words may sit between the words of a keyword. */
    private static final int EXTRA_WORDS_ALLOWED = 1;

    /** Words shorter than this must match exactly (apart from the plural). */
    private static final int MIN_FLEXIBLE_LENGTH = 7;

    private static final Pattern VERSION_TAG = Pattern.compile("\\((?:v\\d+|q\\d+)\\)");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = WORD.matcher(VERSION_TAG.matcher(text.toLowerCase()).replaceAll(" "));
        while (m.find()) {
            String word = SPELLINGS.getOrDefault(m.group(), m.group());
            if (!STOP_WORDS.contains(word)) out.add(word);
        }
        return out;
    }

    private static String singular(String word) {
        return word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")
                ? word.substring(0, word.length() - 1) : word;
    }

    private static String stem(String word) {
        for (String ending : ENDINGS) {
            if (word.endsWith(ending) && word.length() - ending.length() >= 5) {
                return word.substring(0, word.length() - ending.length());
            }
        }
        return word;
    }

    /** 2 = same word, 1 = same word with a different ending or inside a compound, 0 = different words. */
    private static int wordMatch(String keywordWord, String itemWord, boolean singleWordKeyword) {
        if (keywordWord.equals(itemWord) || singular(keywordWord).equals(singular(itemWord))) return 2;
        if (singleWordKeyword) return 0;
        if (keywordWord.length() < MIN_FLEXIBLE_LENGTH || itemWord.length() < MIN_FLEXIBLE_LENGTH) return 0;
        if (stem(keywordWord).equals(stem(itemWord))) return 1;
        if (itemWord.endsWith(keywordWord)
                && COMPOUND_PREFIXES.contains(itemWord.substring(0, itemWord.length() - keywordWord.length()))) return 1;
        return 0;
    }

    /** A keyword's words, each once ("Particle Board Box bed Particle Board" names four words). */
    private static List<String> keywordWords(String keyword) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(words(keyword)));
    }

    /**
     * The most keyword words found together in one item: each in a different word of one run of consecutive
     * item words, the run being as long as the keyword plus the extra words allowed.
     */
    private static int wordsFound(List<String> keywordWords, List<String> itemWords) {
        int n = keywordWords.size();
        boolean single = n == 1;
        int runLength = single ? 1 : n + EXTRA_WORDS_ALLOWED;
        int best = 0;
        for (int start = 0; start < itemWords.size() && best < n; start++) {
            int end = Math.min(itemWords.size(), start + runLength);
            boolean[] used = new boolean[end - start];
            boolean[] done = new boolean[n];
            int found = 0;
            // The same word claims its place first, then a word that differs in ending or prefix.
            for (int quality = 2; quality >= 1; quality--) {
                for (int k = 0; k < n; k++) {
                    if (done[k]) continue;
                    for (int i = start; i < end; i++) {
                        if (!used[i - start] && wordMatch(keywordWords.get(k), itemWords.get(i), single) == quality) {
                            used[i - start] = true;
                            done[k] = true;
                            found++;
                            break;
                        }
                    }
                }
            }
            best = Math.max(best, found);
        }
        return best;
    }

    public static boolean matches(String keyword, String itemName) {
        List<String> keywordWords = keywordWords(keyword);
        if (keywordWords.isEmpty() || itemName == null) return false;
        for (String item : itemName.split("[,;]")) {
            if (wordsFound(keywordWords, words(item)) == keywordWords.size()) return true;
        }
        return false;
    }

    /** The first keyword that matches the item name, or null. */
    public static String firstMatch(List<String> keywords, String itemName) {
        if (keywords == null || itemName == null || itemName.isBlank()) return null;
        for (String keyword : keywords) {
            if (keyword != null && matches(keyword, itemName)) return keyword;
        }
        return null;
    }

    /**
     * A keyword of three or more words that the item misses by exactly one word, or null. Used only to report
     * rejected bids that came close, so a gap in the keyword list is noticed. Two-word keywords are left out:
     * one word of two matches far too many unrelated items.
     */
    public static String nearMiss(List<String> keywords, String itemName) {
        if (keywords == null || itemName == null || itemName.isBlank()) return null;
        for (String keyword : keywords) {
            List<String> keywordWords = keywordWords(keyword);
            if (keywordWords.size() < 3) continue;
            for (String item : itemName.split("[,;]")) {
                if (wordsFound(keywordWords, words(item)) == keywordWords.size() - 1) return keyword;
            }
        }
        return null;
    }

    /** The text sent to GeM's search for a keyword: hyphens, slashes and apostrophes make that search find nothing. */
    public static String searchText(String keyword) {
        if (keyword == null) return "";
        return keyword.replace("'", "").replaceAll("[-/]", " ").replaceAll("\\s+", " ").trim();
    }
}
