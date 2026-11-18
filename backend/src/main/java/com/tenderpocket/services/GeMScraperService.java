package com.tenderpocket.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.TenderRepository;
import com.tenderpocket.utils.TenderClassifier;
import com.tenderpocket.utils.TenderKeywords;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeMScraperService {

    @Autowired
    private TenderRepository tenderRepository;

    @Value("${aws.s3.enabled:false}")
    private boolean awsS3Enabled;

    @Value("${file.upload-dir:public/documents}")
    private String uploadDir = "public/documents";

    @Autowired(required = false)
    private ObjectStorageService objectStorageService;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    /**
     * GeM's /all-bids-data endpoint returns a fixed 10 results per request; a "rows" or page-size
     * parameter is ignored. Pagination is therefore the only way to see past the first 10 matches,
     * and measurement against the live endpoint showed a single keyword ("Hospital Bed") reporting
     * numFound = 105 while only 10 docs came back.
     */
    private static final int GEM_PAGE_SIZE = 10;

    /**
     * Sorting by Bid-End-Date-Oldest (the previous hardcoded value) returns the bids closest to
     * closing. That set barely changes until those bids actually close, so every sync re-fetched
     * the same rows, the deduplication check rejected all of them, and the sync reported
     * "Imported: 0" while newly published bids - which have distant end dates and therefore sort
     * last - were never reached. Bid-Start-Date-Latest puts the most recently published bids on
     * the first page, which is what an incremental sync needs.
     */
    /** Command that starts Python 3 for scripts/parse-gem-pdf.py; empty means detect it. */
    @Value("${python.command:}")
    private String pythonCommand;

    @Value("${gem.sort:Bid-Start-Date-Latest}")
    private String gemSort;

    /** Safety ceiling on requests per keyword, so a logic error cannot become thousands of calls. */
    @Value("${gem.max-pages-per-keyword:15}")
    private int maxPagesPerKeyword;

    /**
     * Stop paging a keyword once this many consecutive results are already in the database. With
     * newest-first ordering that means we have caught up with the last sync. Two pages' worth by
     * default; set higher to tolerate out-of-order results, or lower for faster syncs.
     */
    @Value("${gem.stop-after-known:20}")
    private int stopAfterKnown;

    /** Pause between requests to GeM. Unchanged from the previous hardcoded 2500ms. */
    @Value("${gem.request-pause-ms:2500}")
    private long requestPauseMs;

    /**
     * Whether GeM's "page" parameter counts from 1 or 0. Verified against the live endpoint by
     * comparing the result set for an explicit page against the result set for no page parameter
     * at all. Exposed as configuration so it can be corrected without a rebuild.
     */
    @Value("${gem.first-page-index:1}")
    private int firstPageIndex;

    /**
     * Stop a sync once this many keywords in a row failed on their first page even after a fresh
     * session: GeM is then refusing all searches, and carrying on through 130 keywords only extends
     * the block. 0 disables the check.
     */
    @Value("${gem.max-consecutive-failed-keywords:5}")
    private int maxConsecutiveFailedKeywords;

    static boolean shouldStopAfterFailures(int consecutiveFailedKeywords, int limit) {
        return limit > 0 && consecutiveFailedKeywords >= limit;
    }

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String cachedCookieHeader = null;
    private String cachedCsrfHash = null;

    /** Shared with the state portal crawler; see {@link TenderKeywords}. */
    private static final List<String> RAW_KEYWORDS = TenderKeywords.RAW_KEYWORDS;

    public int syncTenders(boolean allDates, List<String> customKeywords) throws Exception {
        return syncTendersDetailed(allDates, customKeywords).imported;
    }

    /** Outcome of one GeM sync: how many bids were imported and which keywords did not finish. */
    public static final class SyncResult {
        public final int imported;
        /** Keywords whose search failed (even after re-authentication) or were never reached because the sync stopped early. */
        public final List<String> failedKeywords;

        SyncResult(int imported, List<String> failedKeywords) {
            this.imported = imported;
            this.failedKeywords = List.copyOf(failedKeywords);
        }

        public boolean hasFailures() {
            return !failedKeywords.isEmpty();
        }
    }

    @Autowired(required = false)
    private FetchStatusRecorder fetchStatusRecorder;

    /** Thrown when a GeM sync is requested while another one is still running. */
    public static class SyncAlreadyRunningException extends Exception {
        public SyncAlreadyRunningException() {
            super("A GeM sync is already running");
        }
    }

    /** Only one GeM sync at a time: parallel runs multiply the load on GeM and trigger its rate limiting. */
    private final java.util.concurrent.atomic.AtomicBoolean syncRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    public boolean isSyncRunning() {
        return syncRunning.get();
    }

    /**
     * Like {@link #syncTenders} but also reports the keywords that failed, so a retry can repeat only those.
     *
     * @throws SyncAlreadyRunningException when another GeM sync (scheduled or manual) is in progress
     */
    public SyncResult syncTendersDetailed(boolean allDates, List<String> customKeywords) throws Exception {
        if (!syncRunning.compareAndSet(false, true)) {
            System.out.println("[GeM] A GeM sync is already running; this request was skipped.");
            throw new SyncAlreadyRunningException();
        }
        try {
            return syncWithStatus(allDates, customKeywords);
        } finally {
            syncRunning.set(false);
        }
    }

    private SyncResult syncWithStatus(boolean allDates, List<String> customKeywords) throws Exception {
        List<String> searched = customKeywords == null ? List.of() : customKeywords;
        if (fetchStatusRecorder != null) fetchStatusRecorder.markStarted(List.of(FetchStatusRecorder.GEM));
        try {
            SyncResult result = runSync(allDates, customKeywords);
            if (fetchStatusRecorder != null) {
                fetchStatusRecorder.recordGem(searched, result.failedKeywords, result.imported, false);
            }
            return result;
        } catch (Exception e) {
            if (fetchStatusRecorder != null) fetchStatusRecorder.recordGem(searched, List.of(), 0, true);
            throw e;
        } finally {
            if (fetchStatusRecorder != null) fetchStatusRecorder.markFinished(List.of(FetchStatusRecorder.GEM));
        }
    }

    private SyncResult runSync(boolean allDates, List<String> customKeywords) throws Exception {
        List<String> keywords = customKeywords != null && !customKeywords.isEmpty() ? customKeywords : RAW_KEYWORDS;
        int importCount = 0;
        List<String> failedKeywords = new ArrayList<>();

        System.out.println("--- GeM Java Sync Engine Started ---");
        System.out.println("Processing keyword count: " + keywords.size());
        int consecutiveFailedKeywords = 0;

        for (int i = 0; i < keywords.size(); i++) {
            String keyword = keywords.get(i).trim();
            if (keyword.length() < 3) continue;
            if (shouldStopAfterFailures(consecutiveFailedKeywords, maxConsecutiveFailedKeywords)) {
                System.err.println(String.format(
                        "[GeM] %d keywords in a row failed even with a fresh session; GeM is refusing searches "
                                + "(likely rate limiting). Stopping this sync at keyword %d/%d; the next scheduled sync will retry.",
                        consecutiveFailedKeywords, i + 1, keywords.size()));
                for (int j = i; j < keywords.size(); j++) {
                    String rest = keywords.get(j).trim();
                    if (rest.length() >= 3) failedKeywords.add(rest);
                }
                break;
            }
            System.out.println(String.format("[%d/%d] Querying GeM for: \"%s\"...", i + 1, keywords.size(), keyword));

            int page = firstPageIndex;
            int pagesFetched = 0;
            int consecutiveKnown = 0;
            boolean caughtUp = false;
            Set<String> seenBefore = seenBidsFor(keyword);
            Set<String> seenNow = new HashSet<>();

            while (!caughtUp && pagesFetched < maxPagesPerKeyword) {
                JsonNode docs = searchKeywordWithRetry(keyword, page);
                pagesFetched++;

                // null means the page failed twice, including after re-authentication. Give up on
                // this keyword rather than silently treating it as "no more results".
                if (docs == null) {
                    if (pagesFetched == 1) consecutiveFailedKeywords++;
                    failedKeywords.add(keyword);
                    break;
                }
                if (pagesFetched == 1) consecutiveFailedKeywords = 0;

                System.out.println(String.format("  - page %d: %d ongoing bid(s) for \"%s\"",
                        page, docs.size(), keyword));
                if (docs.size() == 0) break;

                for (JsonNode doc : docs) {
                  try {
                    String bId = getJsonString(doc, "b_id");
                    String bidNo = getJsonString(doc, "b_bid_number");

                    if (bId == null || bidNo == null) continue;

                    // Cross-source deduplication and existing record lookup:
                    // 1) Match by official bid number (refNo) if not placeholder
                    // 2) Match by parent bid number if not placeholder (an RA is stored under its parent bid)
                    // 3) Match by GeM bId ONLY if existing record belongs to GeM namespace or bid numbers match
                    // source_id is not used here: it holds the T247 ID, which GeM does not have.
                    Optional<Tender> existingOpt = Optional.empty();
                    if (!TenderClassifier.isPlaceholderRefNo(bidNo)) {
                        existingOpt = tenderRepository.findFirstByRefNo(bidNo);
                    }
                    if (existingOpt.isEmpty()) {
                        String bnp = getJsonString(doc, "b_bid_number_parent");
                        if (bnp != null && !TenderClassifier.isPlaceholderRefNo(bnp)) {
                            existingOpt = tenderRepository.findFirstByRefNo(bnp);
                        }
                    }
                    if (existingOpt.isEmpty()) {
                        Optional<Tender> byId = tenderRepository.findById(bId);
                        if (byId.isPresent() && isLegitimateGeMMatch(byId.get(), bId, bidNo)) {
                            existingOpt = byId;
                        }
                    }

                    if (existingOpt.isPresent()) {
                        Tender existing = existingOpt.get();
                        String bIdParent = getJsonString(doc, "b_id_parent");
                        String docDownloadId = bIdParent != null ? bIdParent : bId;
                        String officialUrl = "https://bidplus.gem.gov.in/showbidDocument/" + docDownloadId;
                        String bidNoParent = getJsonString(doc, "b_bid_number_parent");
                        String officialRefNo = bidNoParent != null ? bidNoParent : bidNo;

                        enrichExistingRecordFromGeM(existing, officialRefNo, bidNo, officialUrl, doc);
                        tenderRepository.save(existing);
                        // Only after the save above, so the PDF link and the details read from the
                        // PDF are not overwritten (that made every run download the same PDFs again).
                        if (needsGemPdf(existing)) {
                            downloadAndParseGemPdf(existing.getId(), officialUrl, bidNo, doc);
                        }
                        System.out.println(String.format("  - Enriched existing tender id=%s refNo=%s from GeM",
                                existing.getId(), existing.getRefNo()));

                        if (awsS3Enabled && objectStorageService != null && objectStorageService.isRemote()) {
                            retryPendingS3UploadIfPresent(existing.getId());
                        }
                        seenNow.add(bidNo);
                        consecutiveKnown++;
                        if (consecutiveKnown >= stopAfterKnown) {
                            System.out.println(String.format(
                                    "  - caught up on \"%s\" after %d consecutive known bid(s)",
                                    keyword, consecutiveKnown));
                            caughtUp = true;
                            break;
                        }
                        continue;
                    }

                    String start = getJsonString(doc, "final_start_date_sort");
                    String formattedStartDate = (start != null && start.contains("T")) ? start.split("T")[0] : "";

                    // Date filter: Fetch tenders published in the last 2 days (India Standard Time)
                    // With newest-first ordering, once a bid's start date is older than the last 2 days cutoff, stop paging for this keyword
                    if (!allDates) {
                        LocalDate todayIST = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
                        LocalDate cutoffDate = todayIST.minusDays(1); // Last 2 days: today and yesterday
                        LocalDate bidStartDate = null;
                        if (start != null && !start.isBlank()) {
                            try {
                                if (start.endsWith("Z")) {
                                    bidStartDate = java.time.Instant.parse(start).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
                                } else {
                                    bidStartDate = LocalDate.parse(formattedStartDate);
                                }
                            } catch (Exception ignored) {
                                try { bidStartDate = LocalDate.parse(formattedStartDate); } catch (Exception ignored2) {}
                            }
                        }

                        if (bidStartDate != null) {
                            if (bidStartDate.isBefore(cutoffDate)) {
                                System.out.println(String.format(
                                        "  - reached bid %s dated %s prior to last 2 days cutoff (%s) for \"%s\"; stopping paging.",
                                        bidNo, formattedStartDate, cutoffDate, keyword));
                                caughtUp = true;
                                break;
                            }
                        } else if (!formattedStartDate.isEmpty() && formattedStartDate.compareTo(cutoffDate.toString()) < 0) {
                            System.out.println(String.format(
                                    "  - reached bid %s dated %s prior to last 2 days cutoff (%s) for \"%s\"; stopping paging.",
                                    bidNo, formattedStartDate, cutoffDate, keyword));
                            caughtUp = true;
                            break;
                        }
                    }

                    // Checked under this keyword in an earlier run and not imported (it did not
                    // match a product). Counts as "known" like a saved tender, so a run that has
                    // caught up stops after a couple of pages instead of re-reading the same
                    // unrelated bids every time.
                    if (seenBefore.contains(bidNo)) {
                        seenNow.add(bidNo);
                        consecutiveKnown++;
                        if (consecutiveKnown >= stopAfterKnown) {
                            System.out.println(String.format(
                                    "  - caught up on \"%s\" after %d bid(s) already checked earlier",
                                    keyword, consecutiveKnown));
                            caughtUp = true;
                            break;
                        }
                        continue;
                    }
                    consecutiveKnown = 0;

                    String items = getJsonString(doc, "b_category_name");
                    String dept = getJsonString(doc, "ba_official_details_deptName");

                    // Require at least 1 matching keyword before fetching/importing tender from GeM portal
                    String fullSearchableText = (items != null ? items : "") + " " + (dept != null ? dept : "") + " " + doc.toString();
                    int keywordMatches = countMatchingKeywords(fullSearchableText, keywords);
                    if (keywordMatches < 1) {
                        System.out.println(String.format("  - Skipping GeM Bid %s (\"%s\"): No keyword matched.", bidNo, items));
                        seenNow.add(bidNo);
                        continue;
                    }

                    importCount++;
                    String qty = getJsonString(doc, "b_total_quantity");
                    String end = getJsonString(doc, "final_end_date_sort");

                    String bIdParent = getJsonString(doc, "b_id_parent");
                    String docDownloadId = bIdParent != null ? bIdParent : bId;
                    String url = "https://bidplus.gem.gov.in/showbidDocument/" + docDownloadId;

                    String bidNoParent = getJsonString(doc, "b_bid_number_parent");
                    String officialRefNo = bidNoParent != null ? bidNoParent : bidNo;

                    Map<String, String> loc = parseLocationFromDept(dept);
                    Map<String, String> defaults = computeDefaultBusinessMetadata(items, officialRefNo, loc.get("place") + ", " + loc.get("state"), url);

                    String startDateTimeStr = formatDateTime(start);
                    String endDateTimeStr = end != null ? formatDateTime(end) : defaults.get("due_date");

                    // Prevent primary key collision with unrelated record from another namespace
                    String tenderId = bId;
                    if (tenderRepository.existsById(bId)) {
                        tenderId = "gem-" + bId;
                    }

                    Tender tender = new Tender();
                    tender.setId(tenderId);
                    tender.setRefNo(officialRefNo);
                    tender.setTitle(items);
                    tender.setAuthority(dept);
                    tender.setEstimatedCost(null);
                    tender.setEstimatedCostRaw("N/A");
                    tender.setEmd(null);
                    tender.setEmdRaw("N/A");
                    tender.setDocumentFee(null);
                    tender.setDocumentFeeRaw("N/A");
                    tender.setLocation(loc.get("place") + ", " + loc.get("state"));
                    tender.setSector(null);
                    tender.setDueDate(endDateTimeStr);
                    tender.setOpeningDate(null);
                    tender.setDocumentUrl(null);
                    tender.setOriginalUrl(url);
                    tender.setStatus("Issued");
                    tender.setScrapedAt(new Date().toString());
                    tender.setNotes(withGemRaNumberNote(
                            String.format("Imported automatically by daily GeM Sync job matching keyword \"%s\".", keyword),
                            officialRefNo, bidNo));
                    tender.setEntryDate(defaults.get("entry_date"));
                    tender.setMisExecutive("");
                    tender.setSource("GeM");
                    // GeM has no T247 ID, so source_id stays empty until the Tender247 email for this bid arrives
                    tender.setVerticalName(defaults.get("vertical_name"));
                    tender.setPlace(loc.get("place"));
                    tender.setState(loc.get("state"));
                    tender.setPublishDate(formattedStartDate);
                    tender.setStartDate(startDateTimeStr);
                    tender.setTime("N/A");
                    tender.setPreBidDate("No");
                    tender.setCorrigendumRemark("No");
                    tender.setProductNameAsPerTender(items);
                    tender.setProductNameAsPerMarken(defaults.get("vertical_name"));
                    tender.setBidQty(qty != null ? Integer.parseInt(qty) : 1);
                    tender.setQuotedQty(qty != null ? Integer.parseInt(qty) : 1);

                    tenderRepository.save(tender);
                    System.out.println(String.format("  - Registered new GeM Bid: %s", bidNo));
                    seenNow.add(bidNo);

                    // Download document PDF and run python PDF parser
                    downloadAndParseGemPdf(tender.getId(), url, bidNo, doc);
                  } catch (Exception docError) {
                    // One malformed bid must not cost the rest of the keyword. Before pagination
                    // that only lost the remainder of a single page; now it would lose every
                    // remaining page too, so each bid is isolated.
                    System.err.println(String.format("  - Skipping a bid for \"%s\": %s",
                            keyword, docError.getMessage() != null ? docError.getMessage() : docError.toString()));
                  }
                }

                // A short page means GeM has no further matches for this keyword.
                if (docs.size() < GEM_PAGE_SIZE) caughtUp = true;

                page++;
                try {
                    Thread.sleep(requestPauseMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.err.println("[GeM Sync] Interrupted; returning early with " + importCount + " import(s).");
                    return new SyncResult(importCount, failedKeywords);
                }
            }

            // Remember only when the keyword finished: if a page failed, the retry must read it
            // all again instead of stopping early on bids from the pages that did load.
            if (!failedKeywords.contains(keyword)) rememberSeenBids(keyword, seenNow);
        }

        return new SyncResult(importCount, failedKeywords);
    }

    /** Bid numbers already checked for a keyword in earlier runs, kept for {@link #SEEN_BID_KEEP_DAYS} days. */
    private final Map<String, Map<String, LocalDate>> seenBidsByKeyword = new java.util.concurrent.ConcurrentHashMap<>();

    /** Bids older than this fall outside the 2-day search window anyway. */
    static final int SEEN_BID_KEEP_DAYS = 3;

    Set<String> seenBidsFor(String keyword) {
        Map<String, LocalDate> seen = seenBidsByKeyword.get(keyword.toLowerCase(Locale.ROOT));
        return seen == null ? Set.of() : new HashSet<>(seen.keySet());
    }

    void rememberSeenBids(String keyword, Set<String> bidNos) {
        if (bidNos.isEmpty()) return;
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        Map<String, LocalDate> seen = seenBidsByKeyword.computeIfAbsent(
                keyword.toLowerCase(Locale.ROOT), k -> new java.util.concurrent.ConcurrentHashMap<>());
        for (String b : bidNos) seen.put(b, today);
        seen.values().removeIf(d -> d.isBefore(today.minusDays(SEEN_BID_KEEP_DAYS)));
    }

    /**
     * When GeM returns a Reverse Auction (RA), the tender is stored under its parent bid number and the RA
     * number is added to the notes so it stays visible. Nothing is added when the RA number equals the stored
     * bid number or is already in the notes.
     */
    static String withGemRaNumberNote(String notes, String storedRefNo, String bidNo) {
        String current = notes == null ? "" : notes;
        if (bidNo == null || TenderClassifier.isPlaceholderRefNo(bidNo)) return current;
        String ra = bidNo.trim();
        if (storedRefNo != null && ra.equalsIgnoreCase(storedRefNo.trim())) return current;
        if (current.toUpperCase(Locale.ROOT).contains(ra.toUpperCase(Locale.ROOT))) return current;
        return (current.isBlank() ? "" : current + "\n") + "GeM RA number: " + ra;
    }

    private boolean isLegitimateGeMMatch(Tender existing, String bId, String bidNo) {
        if (existing == null) return false;
        // 1. Authoritative official bid number matches
        if (!TenderClassifier.isPlaceholderRefNo(bidNo) && !TenderClassifier.isPlaceholderRefNo(existing.getRefNo())) {
            if (bidNo.equalsIgnoreCase(existing.getRefNo())) {
                return true;
            }
        }
        // 2. Same source namespace ("GeM") with same internal ID
        if ("GeM".equalsIgnoreCase(existing.getSource())) {
            if (bId != null && bId.equalsIgnoreCase(existing.getId())) {
                return true;
            }
        }
        // If existing record belongs to another source (e.g. Tender247) and bid numbers don't match, reject match
        return false;
    }

    private void enrichExistingRecordFromGeM(Tender existing, String officialRefNo, String bidNo, String officialUrl, JsonNode doc) {
        if ((existing.getRefNo() == null || existing.getRefNo().isBlank() || TenderClassifier.isPlaceholderRefNo(existing.getRefNo()))
                && !TenderClassifier.isPlaceholderRefNo(officialRefNo)) {
            existing.setRefNo(officialRefNo);
        }
        // The RA number is kept in the notes; source_id is only for the T247 ID
        existing.setNotes(withGemRaNumberNote(existing.getNotes(), existing.getRefNo(), bidNo));
        if (existing.getOriginalUrl() == null || !existing.getOriginalUrl().contains("gem.gov.in")) {
            existing.setOriginalUrl(officialUrl);
        }

        String items = getJsonString(doc, "b_category_name");
        String dept = getJsonString(doc, "ba_official_details_deptName");
        String qty = getJsonString(doc, "b_total_quantity");
        String start = getJsonString(doc, "final_start_date_sort");
        String end = getJsonString(doc, "final_end_date_sort");

        if (existing.getTitle() == null || existing.getTitle().isBlank() || existing.getTitle().startsWith("Tender ")) {
            if (items != null && !items.isBlank()) {
                existing.setTitle(items);
            }
        }
        if (existing.getAuthority() == null || existing.getAuthority().isBlank()) {
            if (dept != null && !dept.isBlank()) {
                existing.setAuthority(dept);
            }
        }
        if (existing.getDueDate() == null || existing.getDueDate().isBlank()) {
            if (end != null) {
                existing.setDueDate(formatDateTime(end));
            }
        }
        if (existing.getStartDate() == null || existing.getStartDate().isBlank()) {
            if (start != null) {
                existing.setStartDate(formatDateTime(start));
            }
        }
        if (existing.getPublishDate() == null || existing.getPublishDate().isBlank()) {
            if (start != null && start.contains("T")) {
                existing.setPublishDate(start.split("T")[0]);
            }
        }
        if (existing.getBidQty() == null || existing.getBidQty() <= 0) {
            if (qty != null) {
                try { existing.setBidQty(Integer.parseInt(qty)); } catch (Exception ignored) {}
            }
        }
        if (existing.getQuotedQty() == null || existing.getQuotedQty() <= 0) {
            if (qty != null) {
                try { existing.setQuotedQty(Integer.parseInt(qty)); } catch (Exception ignored) {}
            }
        }

        // The bid PDF is attached by the caller after this record is saved: downloadAndParseGemPdf
        // saves its own copy of the tender, which a later save of this (older) object would undo.
    }

    /** True when an existing tender still needs its official GeM bid PDF. */
    static boolean needsGemPdf(Tender t) {
        return t.getDocumentUrl() == null || t.getDocumentUrl().isBlank();
    }

    private void retryPendingS3UploadIfPresent(String tenderId) {
        try {
            Optional<Tender> opt = tenderRepository.findById(tenderId);
            if (opt.isPresent()) {
                Tender t = opt.get();
                if (t.getNotes() != null && t.getNotes().contains("[S3_UPLOAD_PENDING:")) {
                    String localFileName = "Bid_Document_" + tenderId + ".pdf";
                    Path localFilePath = Paths.get(uploadDir, tenderId, localFileName);
                    if (Files.exists(localFilePath)) {
                        String s3Key = "tenders/" + tenderId + "/" + localFileName;
                        byte[] pdfBytes = Files.readAllBytes(localFilePath);
                        objectStorageService.upload(s3Key, new ByteArrayInputStream(pdfBytes), "application/pdf", pdfBytes.length);
                        t.setNotes(t.getNotes().replaceAll("\\[S3_UPLOAD_PENDING:[^\\]]+\\]\\s*", ""));
                        tenderRepository.save(t);
                        System.out.println("  - [GeM Scraper] Successfully retried S3 upload for tender: " + tenderId);
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println("  - [GeM Scraper] Retry S3 upload failed for tender " + tenderId + ": " + ex.getMessage());
        }
    }

    private void downloadAndParseGemPdf(String tenderId, String downloadUrl, String bidNo, JsonNode doc) {
        try {
            String localDir = Paths.get(uploadDir, tenderId).toString();
            String localFileName = "Bid_Document_" + tenderId + ".pdf";
            String outputPath = localDir + "/" + localFileName;
            String localPath = "/documents/" + tenderId + "/" + localFileName;

            Files.createDirectories(Paths.get(localDir));
            
            System.out.println("  - Downloading Bid PDF to " + outputPath + "...");
            boolean success = downloadFile(downloadUrl, outputPath);
            if (success) {
                boolean s3UploadSuccess = true;
                String s3ErrorMessage = null;
                if (awsS3Enabled && objectStorageService != null && objectStorageService.isRemote()) {
                    try {
                        String s3Key = "tenders/" + tenderId + "/" + localFileName;
                        byte[] pdfBytes = Files.readAllBytes(Paths.get(outputPath));
                        objectStorageService.upload(s3Key, new ByteArrayInputStream(pdfBytes), "application/pdf", pdfBytes.length);
                        System.out.println("  - Successfully uploaded Bid PDF to S3: " + s3Key);
                    } catch (Exception s3Ex) {
                        s3UploadSuccess = false;
                        s3ErrorMessage = s3Ex.getMessage();
                        System.err.println("  - [GeM Scraper] Cloud S3 upload failed for tender " + tenderId + " (local copy preserved at " + outputPath + "): " + s3ErrorMessage);
                    }
                }

                // Call external python parser ("python3" on Linux/Docker, "python" or "py -3" on Windows)
                List<String> command = new ArrayList<>(com.tenderpocket.utils.PythonCommand.resolve(pythonCommand));
                command.add("scripts/parse-gem-pdf.py");
                command.add(outputPath);
                ProcessBuilder pb = new ProcessBuilder(command);
                // stderr goes to a file so an unread error stream can never block the script,
                // and its message can be shown when parsing fails (e.g. pypdf not installed).
                File errFile = File.createTempFile("parse-gem-pdf-", ".err");
                pb.redirectError(errFile);
                // Windows Python writes cp1252 by default; the JSON is read back as UTF-8.
                pb.environment().put("PYTHONIOENCODING", "utf-8");
                Process process = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    String err = Files.readString(errFile.toPath()).trim();
                    String lastLine = err.isEmpty() ? "no error output" : err.substring(err.lastIndexOf('\n') + 1);
                    System.err.println("  - PDF parser exited with code " + exitCode + " for " + tenderId + ": " + lastLine);
                }
                Files.deleteIfExists(errFile.toPath());
                Optional<Tender> opt = tenderRepository.findById(tenderId);
                if (opt.isPresent()) {
                    Tender t = opt.get();
                    t.setDocumentUrl(localPath);
                    if (!s3UploadSuccess && s3ErrorMessage != null) {
                        String warningNote = "[S3_UPLOAD_PENDING: " + s3ErrorMessage + " | Local copy preserved at " + localPath + "]";
                        String currentNotes = t.getNotes();
                        t.setNotes(currentNotes != null && !currentNotes.isBlank() ? warningNote + " " + currentNotes : warningNote);
                    }
                    if (exitCode == 0) {
                        JsonNode parsed = objectMapper.readTree(sb.toString());
                        String place = parsed.has("place") ? parsed.get("place").asText() : "N/A";
                        String state = parsed.has("state") ? parsed.get("state").asText() : "N/A";
                        String dueDate = parsed.has("due_date") ? parsed.get("due_date").asText() : null;
                        String dueTime = parsed.has("due_time") ? parsed.get("due_time").asText() : null;
                        Double emdAmount = parsed.has("emd_amount") && !parsed.get("emd_amount").isNull() ? parsed.get("emd_amount").asDouble() : null;

                        if (!"N/A".equals(place)) {
                            t.setPlace(place);
                            t.setLocation(place + ", " + state);
                        }
                        if (!"N/A".equals(state)) t.setState(state);
                        String storedDueDate = t.getDueDate();
                        boolean storedUsable = storedDueDate != null
                                && !storedDueDate.isBlank()
                                && !"N/A".equalsIgnoreCase(storedDueDate.trim());
                        if (dueDate != null && !storedUsable) {
                            t.setDueDate(dueDate + (dueTime != null ? " " + dueTime : ""));
                        }
                        if (emdAmount != null) {
                            t.setEmd(emdAmount);
                            t.setEmdRaw("₹" + emdAmount);
                        }
                        t.setDownloadedDocs(String.format("[{\"name\":\"Bid Document\",\"filename\":\"%s\",\"local_path\":\"%s\",\"created_date\":\"%s\"}]", 
                                localFileName, localPath, LocalDate.now().toString()));
                    }
                    tenderRepository.save(t);
                    System.out.println("  - Successfully updated PDF metadata for tender " + tenderId);
                }
            }
        } catch (Exception e) {
            System.err.println("  - Failed to parse PDF: " + e.getMessage());
        }
    }

    private boolean downloadFile(String downloadUrl, String outputPath) {
        try {
            byte[] data = restTemplate.getForObject(downloadUrl, byte[].class);
            if (data != null) {
                try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                    fos.write(data);
                }
                return true;
            }
        } catch (Exception e) {
            System.err.println("Download failed: " + e.getMessage());
        }
        return false;
    }

    private HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(java.time.Duration.ofSeconds(15));

        String proxyHost = System.getenv("HTTP_PROXY_HOST");
        String proxyPort = System.getenv("HTTP_PROXY_PORT");
        if (proxyHost != null && !proxyHost.isEmpty() && proxyPort != null && !proxyPort.isEmpty()) {
            try {
                builder.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(proxyHost, Integer.parseInt(proxyPort))));
            } catch (Exception e) {
                System.err.println("Failed to set HTTP proxy: " + e.getMessage());
            }
        }
        return builder.build();
    }

    /**
     * Fetches one page for a keyword, retrying once after discarding the cached session.
     * Returns null when both attempts fail, which the caller treats as "abandon this keyword"
     * rather than "no more results" - the previous code skipped a failed keyword outright,
     * losing roughly 7% of keywords per sync to transient non-200 responses with no retry.
     */
    JsonNode searchKeywordWithRetry(String keyword, int page) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return searchKeyword(keyword, page);
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                System.err.println(String.format(
                        "  - Error syncing GeM for keyword \"%s\" page %d (attempt %d/2): %s",
                        keyword, page, attempt, errorMsg));
                if (errorMsg.contains("CSRF") || errorMsg.contains("session") || errorMsg.contains("non-200")) {
                    cachedCookieHeader = null;
                    cachedCsrfHash = null;
                }
                if (attempt < 2) {
                    try {
                        Thread.sleep(requestPauseMs * 2);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private JsonNode searchKeyword(String keyword, int page) throws Exception {
        getCredentials();

        String url = "https://bidplus.gem.gov.in/all-bids-data";

        Map<String, Object> param = new HashMap<>();
        param.put("searchBid", searchTextFor(keyword));
        param.put("searchType", "fullText");

        Map<String, Object> filter = new HashMap<>();
        filter.put("bidStatusType", "ongoing_bids");
        filter.put("byType", "all");
        filter.put("highBidValue", "");
        filter.put("byEndDate", Map.of("from", "", "to", ""));
        filter.put("sort", gemSort);

        Map<String, Object> payload = new HashMap<>();
        payload.put("param", param);
        payload.put("filter", filter);
        // "page" sits at the top level of the payload, beside param and filter. Verified against
        // the live endpoint: page inside param, a Solr-style "start" offset, and a "rows" page
        // size were all accepted with HTTP 200 and then silently ignored, returning page one.
        payload.put("page", page);

        String payloadStr = objectMapper.writeValueAsString(payload);

        HttpClient client = buildHttpClient();
        String formBody = "payload=" + java.net.URLEncoder.encode(payloadStr, "UTF-8") +
                "&csrf_bd_gem_nk=" + cachedCsrfHash;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(java.time.Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Referer", "https://bidplus.gem.gov.in/all-bids")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cookie", cachedCookieHeader)
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // GeM issues a new CSRF token with every search reply (cookie csrf_gem_cookie, which is also
        // the csrf_bd_gem_nk form value), as a browser session would. Reusing the first token made
        // every search after the first in a session fail with code 500.
        cachedCookieHeader = mergeCookies(cachedCookieHeader, response.headers().allValues("Set-Cookie"));
        String rotatedCsrf = cookieValue(cachedCookieHeader, "csrf_gem_cookie");
        if (rotatedCsrf != null && !rotatedCsrf.isBlank()) cachedCsrfHash = rotatedCsrf;

        JsonNode node;
        try {
            node = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new Exception("GeM search returned HTTP " + response.statusCode() + " with a non-JSON body");
        }
        if (node.has("code") && node.get("code").asInt() == 200) {
            return node.get("response").get("response").get("docs");
        }
        String message = node.has("message") ? node.get("message").asText() : "no message";
        if (isNoResults(node.path("code").asInt(), message)) {
            // GeM answers "404: No data found" when a search simply has no ongoing bids. That is an
            // empty result, not a failure: no retry, no new session, no "missed product" warning.
            return objectMapper.createArrayNode();
        }
        throw new Exception("GeM search returned non-200 status code (HTTP " + response.statusCode()
                + ", code " + node.path("code").asText("?") + ": " + message + ")");
    }

    /** GeM's reply for a search with no ongoing bids. */
    static boolean isNoResults(int code, String message) {
        return code == 404 && message != null && message.toLowerCase(Locale.ROOT).contains("no data");
    }

    /**
     * The text sent to GeM's search for a keyword. GeM's search fails with "500: Something went
     * wrong" whenever the text contains a slash ("Freezer / Deep Freezer"), so slashes become
     * spaces. Matching bids against the keyword list is unchanged.
     */
    static String searchTextFor(String keyword) {
        return keyword == null ? "" : keyword.replace('/', ' ').replace('\\', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Applies Set-Cookie headers to a "name=value; name=value" Cookie header, replacing changed values. */
    static String mergeCookies(String cookieHeader, List<String> setCookies) {
        LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) cookies.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        if (setCookies != null) {
            for (String c : setCookies) {
                String pair = c.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        StringBuilder sb = new StringBuilder();
        cookies.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : "; ").append(k).append('=').append(v));
        return sb.toString();
    }

    /** Value of one cookie in a Cookie header, or null. */
    static String cookieValue(String cookieHeader, String name) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
        }
        return null;
    }

    private synchronized void getCredentials() throws Exception {
        if (cachedCookieHeader != null && cachedCsrfHash != null) {
            return;
        }

        String mainUrl = "https://bidplus.gem.gov.in/all-bids";
        
        HttpClient client = buildHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mainUrl))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(java.time.Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        // Parse cookies
        cachedCookieHeader = mergeCookies(null, response.headers().allValues("Set-Cookie"));

        // Parse CSRF
        String html = response.body();
        Pattern pattern = Pattern.compile("'csrf_bd_gem_nk'\\s*:\\s*'([a-f0-9]+)'", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            cachedCsrfHash = matcher.group(1);
        } else {
            Document doc = Jsoup.parse(html);
            Element csrfInput = doc.selectFirst("input[name=csrf_bd_gem_nk]");
            if (csrfInput != null) {
                cachedCsrfHash = csrfInput.val();
            }
        }

        if (cachedCsrfHash == null) {
            throw new Exception("Failed to negotiate GeM CSRF token");
        }
    }

    private String getJsonString(JsonNode node, String field) {
        if (node.has(field)) {
            JsonNode f = node.get(field);
            if (f.isArray() && f.size() > 0) {
                return f.get(0).asText();
            }
            return f.asText();
        }
        return null;
    }

    private String formatDateTime(String dt) {
        if (dt == null) return null;
        return dt.replace("T", " ").replace("Z", "").split("\\.")[0];
    }

    private Map<String, String> parseLocationFromDept(String dept) {
        Map<String, String> loc = new HashMap<>();
        loc.put("place", "N/A");
        loc.put("state", "N/A");
        if (dept == null) return loc;
        
        List<String> states = Arrays.asList("Andhra Pradesh","Arunachal Pradesh","Assam","Bihar","Chhattisgarh","Goa","Gujarat","Haryana","Himachal Pradesh","Jharkhand","Karnataka","Kerala","Madhya Pradesh","Maharashtra","Manipur","Meghalaya","Mizoram","Nagaland","Odisha","Punjab","Rajasthan","Sikkim","Tamil Nadu","Telangana","Tripura","Uttar Pradesh","Uttarakhand","West Bengal","Delhi","Jammu","Kashmir","Ladakh","Puducherry","Chandigarh");
        String lowerDept = dept.toLowerCase();
        
        for (String s : states) {
            if (lowerDept.contains(s.toLowerCase())) {
                loc.put("state", s);
                break;
            }
        }
        return loc;
    }

    private Map<String, String> computeDefaultBusinessMetadata(String title, String refNo, String location, String originalUrl) {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("entry_date", LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy")));
        
        String vertical = "Others";
        if (title != null) {
            String lowerTitle = title.toLowerCase();
            if (lowerTitle.contains("wheelchair") || lowerTitle.contains("trolley") || lowerTitle.contains("couch") || lowerTitle.contains("screen") || lowerTitle.contains("cart") || lowerTitle.contains("cabinet") || lowerTitle.contains("ward") || lowerTitle.contains("bed") || lowerTitle.contains("furniture") || lowerTitle.contains("table")) {
                vertical = "Medical Equipment & Furniture";
            } else if (lowerTitle.contains("centrifuge")) {
                vertical = "Centrifuge";
            } else if (lowerTitle.contains("freezer") || lowerTitle.contains("deep freezer")) {
                vertical = "Deep Freezer";
            } else if (lowerTitle.contains("hvac") || lowerTitle.contains("conditioning") || lowerTitle.contains("split ac") || lowerTitle.contains("chiller") || lowerTitle.contains("cooling")) {
                vertical = "HVAC";
            }
        }
        defaults.put("vertical_name", vertical);
        defaults.put("due_date", "N/A");
        return defaults;
    }

    public static int countMatchingKeywords(String content, List<String> keywords) {
        return TenderKeywords.countMatchingKeywords(content, keywords);
    }
}
