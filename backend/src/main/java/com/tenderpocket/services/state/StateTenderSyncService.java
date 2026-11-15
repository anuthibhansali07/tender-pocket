package com.tenderpocket.services.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.TenderRepository;
import com.tenderpocket.utils.TenderClassifier;
import com.tenderpocket.utils.TenderKeywords;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Imports relevant tenders from the NIC GePNIC state portals (and the central portal), using only
 * pages that need no CAPTCHA.
 *
 * <p>Per portal: read "Tenders by Organisation", open each department's list (one page holds all
 * of its active tenders), keep rows published within the lookback window that are relevant, open
 * their detail page, then save new tenders or merge into an existing record with the same Tender ID.
 * Portals run in parallel on a dedicated pool; requests within one portal are sequential.
 */
@Service
public class StateTenderSyncService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DB_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ENTRY_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Departments whose generic equipment/furniture purchases are worth keeping without a product keyword. */
    private static final Pattern HEALTH_ORG = Pattern.compile(
            "(?i)health|medical|hospital|aiims|pgimer|jipmer|drug|nhm|family welfare|ayush|ayurved|homoeopath|"
                    + "dental|nursing|esic|employees state insurance|medicine|sanatorium|blood|"
                    // State medical procurement corporations: APMSIDC, TGMSIDC, GMSCL, BMSICL, TNMSC, KMSCL...
                    + "msidc|mscl|msicl|tnmsc|medical services");

    /** Key used to pass a list crawler's document reference into buildTender. */
    static final String PORTAL_DOC_REF = "__portalDocRef";

    /** Crawlers for non-NIC state systems, keyed by the portal "type" in state-portals.json. */
    static final Map<String, PortalListCrawler> CRAWLERS = Map.of(
            "apts", new ApTsEprocCrawler(),
            "nprocure", new NprocureCrawler(),
            "eproc2", new BiharEproc2Crawler(),
            "nggepnic", new NgGepnicLatestCrawler(),
            "chips", new ChipsCrawler());

    @Autowired
    private TenderRepository tenderRepository;

    @Value("${state.sync.enabled:true}")
    private boolean enabled;

    @Value("${state.sync.lookback-days:7}")
    private int lookbackDays;

    @Value("${state.sync.request-pause-ms:1500}")
    private long requestPauseMs;

    @Value("${state.sync.request-timeout-seconds:90}")
    private int requestTimeoutSeconds;

    @Value("${state.sync.parallel-portals:3}")
    private int parallelPortals;

    @Value("${state.sync.max-details-per-portal:400}")
    private int maxDetailsPerPortal;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile List<GepnicPortal> portals = List.of();
    private volatile RunSummary lastRun;
    private ExecutorService pool;

    @PostConstruct
    void init() {
        portals = loadPortals();
        pool = Executors.newFixedThreadPool(Math.max(1, parallelPortals), r -> {
            Thread t = new Thread(r, "state-portal-sync");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Optional one-off import when the app starts: {@code state.sync.run-on-startup=ALL} (every
     * enabled portal) or a code list such as {@code MH,UP}. Empty (the default) does nothing.
     */
    @Value("${state.sync.run-on-startup:}")
    private String runOnStartup;

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void runOnStartupIfConfigured() {
        if (runOnStartup == null || runOnStartup.isBlank() || !enabled) return;
        List<String> codes = "ALL".equalsIgnoreCase(runOnStartup.trim()) ? List.of() : List.of(runOnStartup.split(","));
        System.out.println("[StateSync] state.sync.run-on-startup=" + runOnStartup + ": starting import");
        startAsync(codes, false);
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) pool.shutdownNow();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<GepnicPortal> getPortals() {
        return portals;
    }

    @Value("${state.sync.frequent-portals:MN}")
    private String frequentPortals;

    /** Enabled portals listed in state.sync.frequent-portals (checked every few hours). */
    public List<String> getFrequentPortalCodes() {
        if (frequentPortals == null || frequentPortals.isBlank()) return List.of();
        Set<String> wanted = java.util.Arrays.stream(frequentPortals.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return portals.stream().filter(p -> p.enabled() && wanted.contains(p.code())).map(GepnicPortal::code).toList();
    }

    public boolean isRunning() {
        return running.get();
    }

    public RunSummary getLastRun() {
        return lastRun;
    }

    /**
     * Starts a sync in the background and returns immediately, so it never delays the GeM and
     * Tender247 jobs that share the Spring scheduler thread.
     *
     * @param portalCodes codes to sync; empty means every enabled portal
     * @param dryRun      when true, nothing is written; the summary lists what would be imported
     * @return false if a sync is already running
     */
    public boolean startAsync(Collection<String> portalCodes, boolean dryRun) {
        if (!running.compareAndSet(false, true)) return false;
        Thread runner = new Thread(() -> {
            try {
                runNow(portalCodes, dryRun);
            } finally {
                running.set(false);
            }
        }, "state-portal-sync-runner");
        runner.setDaemon(true);
        runner.start();
        return true;
    }

    /** Runs a sync on the calling thread. Prefer {@link #startAsync} from schedulers and controllers. */
    public RunSummary runNow(Collection<String> portalCodes, boolean dryRun) {
        Set<String> wanted = portalCodes == null ? Set.of()
                : portalCodes.stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        List<GepnicPortal> targets = portals.stream()
                .filter(p -> wanted.isEmpty() ? p.enabled() : wanted.contains(p.code()))
                .toList();

        RunSummary summary = new RunSummary(LocalDateTime.now(IST), dryRun);
        lastRun = summary;
        System.out.printf("[StateSync] Starting %s sync of %d portal(s): %s%n", dryRun ? "DRY-RUN" : "live",
                targets.size(), targets.stream().map(GepnicPortal::code).collect(Collectors.joining(",")));

        List<Future<PortalResult>> futures = new ArrayList<>();
        for (GepnicPortal portal : targets) {
            futures.add(pool.submit(() -> {
                GepnicSession session = new GepnicSession(requestPauseMs, Duration.ofSeconds(requestTimeoutSeconds));
                if (portal.isGepnic()) return syncPortal(portal, session, dryRun);
                PortalListCrawler crawler = CRAWLERS.get(portal.kind());
                if (crawler == null) {
                    PortalResult r = new PortalResult(portal.code());
                    r.error = "No crawler for portal type " + portal.kind();
                    return r;
                }
                return syncListPortal(portal, crawler, session, dryRun);
            }));
        }
        for (Future<PortalResult> f : futures) {
            try {
                summary.portals.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                PortalResult failed = new PortalResult("?");
                failed.error = e.toString();
                summary.portals.add(failed);
            }
        }
        summary.finishedAt = LocalDateTime.now(IST);
        System.out.printf("[StateSync] Finished: imported=%d enriched=%d relevant=%d portals=%d failed=%d%n",
                summary.totalImported(), summary.totalEnriched(), summary.totalRelevant(), summary.portals.size(),
                summary.portals.stream().filter(r -> r.error != null).count());
        return summary;
    }

    /** Syncs one portal with the given fetcher. Package-private for tests. */
    PortalResult syncPortal(GepnicPortal portal, PageFetcher fetcher, boolean dryRun) {
        PortalResult result = new PortalResult(portal.code());
        long started = System.currentTimeMillis();
        LocalDateTime cutoff = LocalDateTime.now(IST).minusDays(lookbackDays);
        try {
            List<GepnicParser.OrganisationLink> orgs = GepnicParser.parseOrganisations(fetcher.get(portal.organisationsUrl()));
            result.organisations = orgs.size();
            for (GepnicParser.OrganisationLink org : orgs) {
                if (org.activeCount() <= 0) continue;
                List<GepnicParser.TenderRow> rows;
                try {
                    rows = GepnicParser.parseTenderList(fetcher.get(org.url()));
                } catch (GepnicSession.PortalBlockedException e) {
                    throw e;
                } catch (Exception e) {
                    result.failures++;
                    System.err.printf("[StateSync] %s: failed to list \"%s\": %s%n", portal.code(), org.name(), e.getMessage());
                    continue;
                }
                result.rowsSeen += rows.size();
                for (GepnicParser.TenderRow row : rows) {
                    if (row.publishedAt() != null && row.publishedAt().isBefore(cutoff)) continue;
                    result.rowsInWindow++;
                    String reason = relevanceReason(row);
                    if (reason == null) continue;
                    result.relevant++;
                    handleRelevantRow(portal, fetcher, row, reason, dryRun, result);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.error = "interrupted";
        } catch (Exception e) {
            result.error = e.getMessage() != null ? e.getMessage() : e.toString();
            System.err.printf("[StateSync] %s failed: %s%n", portal.code(), result.error);
        }
        result.requests = fetcher.requestCount();
        result.durationMs = System.currentTimeMillis() - started;
        System.out.printf("[StateSync] %s done: orgs=%d rows=%d inWindow=%d relevant=%d imported=%d enriched=%d known=%d requests=%d %.1fs%s%n",
                portal.code(), result.organisations, result.rowsSeen, result.rowsInWindow, result.relevant, result.imported,
                result.enriched, result.alreadyKnown, result.requests, result.durationMs / 1000.0,
                result.error != null ? " ERROR " + result.error : "");
        return result;
    }

    /** Why a row is worth importing, or null when it is not. */
    static String relevanceReason(GepnicParser.TenderRow row) {
        String title = row.title();
        // Product words that also describe lifts, tankers and water works ("stretcher lift",
        // "tractor mounted suction machine") are not purchases of our products.
        if (title != null && NOT_OUR_PRODUCTS.matcher(title).find()) return null;
        String keyword = TenderKeywords.firstWholeWordKeyword(title, TenderKeywords.RAW_KEYWORDS);
        if (keyword != null) return "keyword \"" + keyword + "\"";
        String hindi = TenderKeywords.firstHindiKeyword(title);
        if (hindi != null) return "Hindi keyword \"" + hindi + "\"";
        String org = row.organisationChain();
        if (org != null && HEALTH_ORG.matcher(org).find() && !WATER_HEALTH_ORG.matcher(org).find()
                && TenderKeywords.mentionsGenericGoods(title)) {
            return "health department equipment/furniture purchase";
        }
        return null;
    }

    /** Titles that look like a product match but are lifts, tankers, pumping or water supply works. */
    private static final Pattern NOT_OUR_PRODUCTS = Pattern.compile(
            "(?i)\\b(lifts?|elevators?|escalators?|tankers?|tractors?|trailers?|cesspool|pump ?sets?|pumping|intake ?well|water supply|"
                    + "sewer\\w*|desilting|pipeline)\\b");

    /** "Public Health Engineering" and water/sanitation boards are not hospitals. */
    private static final Pattern WATER_HEALTH_ORG = Pattern.compile(
            "(?i)public health engineering|health engineering|phed|water supply|sewerage|sanitation|drinking water|rwss|gwssb");

    private void handleRelevantRow(GepnicPortal portal, PageFetcher fetcher, GepnicParser.TenderRow row,
                                   String reason, boolean dryRun, PortalResult result) throws InterruptedException {
        Optional<Tender> existing = findExisting(row);
        if (existing.isPresent() && isOurs(existing.get(), portal)) {
            result.alreadyKnown++;
            return;
        }
        if (result.detailsFetched >= maxDetailsPerPortal) {
            result.skippedOverCap++;
            return;
        }

        Map<String, String> detail = Collections.emptyMap();
        if (row.detailUrl() != null) {
            try {
                Document d = fetcher.get(row.detailUrl());
                detail = GepnicParser.parseDetail(d);
                result.detailsFetched++;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                result.failures++;
                System.err.printf("[StateSync] %s: detail fetch failed for %s: %s%n", portal.code(), row.tenderId(), e.getMessage());
            }
        }

        // A "Works" tender (construction, repairs) only stays if it named one of our products.
        if (!reason.startsWith("keyword") && "Works".equalsIgnoreCase(detail.getOrDefault("Tender Category", ""))) {
            result.relevant--;
            return;
        }

        if (result.sample.size() < 15) {
            result.sample.add(row.tenderId() + " | " + row.title() + " | " + reason);
        }
        if (dryRun) {
            if (existing.isPresent()) result.enriched++; else result.imported++;
            return;
        }
        try {
            if (existing.isPresent()) {
                enrich(existing.get(), portal, row, detail);
                tenderRepository.save(existing.get());
                result.enriched++;
            } else {
                tenderRepository.save(buildTender(portal, row, detail, reason));
                result.imported++;
            }
        } catch (Exception e) {
            result.failures++;
            System.err.printf("[StateSync] %s: could not save %s: %s%n", portal.code(), row.tenderId(), e.getMessage());
        }
    }

    /**
     * Syncs a non-NIC state portal through its crawler. These lists already carry title, department,
     * dates, value and category, so no detail pages are opened. Package-private for tests.
     */
    PortalResult syncListPortal(GepnicPortal portal, PortalListCrawler crawler, GepnicSession session, boolean dryRun) {
        PortalResult result = new PortalResult(portal.code());
        long started = System.currentTimeMillis();
        LocalDateTime cutoff = LocalDateTime.now(IST).minusDays(lookbackDays);
        try {
            List<PortalListCrawler.ListedTender> listed = crawler.list(portal, session, cutoff);
            result.organisations = (int) listed.stream().map(PortalListCrawler.ListedTender::organisation).distinct().count();
            result.rowsSeen = listed.size();
            for (PortalListCrawler.ListedTender t : listed) {
                if (t.publishedAt() != null && t.publishedAt().isBefore(cutoff)) continue;
                result.rowsInWindow++;
                GepnicParser.TenderRow row = t.toRow();
                String reason = relevanceReason(row);
                if (reason == null) continue;
                // Construction and repair works only stay if they named one of our products.
                if (!reason.startsWith("keyword") && t.category() != null && t.category().equalsIgnoreCase("WORKS")) continue;
                result.relevant++;

                Optional<Tender> existing = findExistingOnListPortal(portal, t);
                if (existing.isPresent() && isOurs(existing.get(), portal)) {
                    result.alreadyKnown++;
                    continue;
                }
                if (result.sample.size() < 15) result.sample.add(t.tenderId() + " | " + t.title() + " | " + reason);
                if (dryRun) {
                    if (existing.isPresent()) result.enriched++; else result.imported++;
                    continue;
                }
                Map<String, String> detail = listDetail(t);
                try {
                    if (existing.isPresent()) {
                        enrich(existing.get(), portal, row, detail);
                        tenderRepository.save(existing.get());
                        result.enriched++;
                    } else {
                        tenderRepository.save(buildTender(portal, row, detail, reason));
                        result.imported++;
                    }
                } catch (Exception e) {
                    result.failures++;
                    System.err.printf("[StateSync] %s: could not save %s: %s%n", portal.code(), t.tenderId(), e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.error = "interrupted";
        } catch (Exception e) {
            result.error = e.getMessage() != null ? e.getMessage() : e.toString();
            System.err.printf("[StateSync] %s failed: %s%n", portal.code(), result.error);
        }
        result.requests = session.requestCount();
        result.durationMs = System.currentTimeMillis() - started;
        System.out.printf("[StateSync] %s done: orgs=%d rows=%d inWindow=%d relevant=%d imported=%d enriched=%d known=%d requests=%d %.1fs%s%n",
                portal.code(), result.organisations, result.rowsSeen, result.rowsInWindow, result.relevant, result.imported,
                result.enriched, result.alreadyKnown, result.requests, result.durationMs / 1000.0,
                result.error != null ? " ERROR " + result.error : "");
        return result;
    }

    /** Portal-prefixed source id for non-NIC portals, e.g. "TS-746793" (their numeric ids are not unique across sources). */
    static String listSourceId(GepnicPortal portal, String tenderId) {
        return portal.code() + "-" + tenderId;
    }

    /**
     * Same tender already stored? Non-NIC tender ids are bare numbers that could equal a Tender247
     * id, so lookups use our own id and portal-prefixed source id, plus the free-text reference
     * when the department also matches.
     */
    Optional<Tender> findExistingOnListPortal(GepnicPortal portal, PortalListCrawler.ListedTender t) {
        Optional<Tender> found = tenderRepository.findById((portal.idPrefix() + t.tenderId()).toLowerCase(Locale.ROOT));
        if (found.isEmpty()) found = tenderRepository.findFirstBySourceId(listSourceId(portal, t.tenderId()));
        if (found.isEmpty() && t.referenceNumber() != null && t.referenceNumber().length() >= 6
                && !TenderClassifier.isPlaceholderRefNo(t.referenceNumber())) {
            Optional<Tender> byRef = tenderRepository.findFirstByRefNo(t.referenceNumber());
            if (byRef.isPresent() && sameDepartment(byRef.get().getAuthority(), t.organisation())) found = byRef;
        }
        return found;
    }

    /** List fields in the shape buildTender reads from a NIC detail page. */
    private static Map<String, String> listDetail(PortalListCrawler.ListedTender t) {
        Map<String, String> d = new java.util.HashMap<>();
        if (t.extra() != null) d.putAll(t.extra());
        if (t.docRef() != null) d.put(PORTAL_DOC_REF, t.docRef());
        if (t.referenceNumber() != null) d.put("Tender Reference Number", t.referenceNumber());
        if (t.category() != null) d.put("Tender Category", t.category());
        if (t.estimatedValue() != null) {
            d.put("Tender Value", java.text.NumberFormat.getNumberInstance(new Locale("en", "IN")).format(t.estimatedValue()));
        }
        return d;
    }

    /**
     * Finds a record for the same tender from any source. The GePNIC Tender ID is unique per
     * portal and is what Tender247 usually stores as the tender number. The free-text reference is
     * reused across departments, so it only counts together with a matching department.
     */
    Optional<Tender> findExisting(GepnicParser.TenderRow row) {
        Optional<Tender> t = tenderRepository.findFirstBySourceId(row.tenderId());
        if (t.isEmpty()) t = tenderRepository.findFirstByRefNo(row.tenderId());
        if (t.isEmpty() && row.referenceNumber() != null && row.referenceNumber().length() >= 6
                && !TenderClassifier.isPlaceholderRefNo(row.referenceNumber())) {
            Optional<Tender> byRef = tenderRepository.findFirstByRefNo(row.referenceNumber());
            if (byRef.isPresent() && sameDepartment(byRef.get().getAuthority(), row.organisationChain())) {
                t = byRef;
            }
        }
        return t;
    }

    private static boolean isOurs(Tender t, GepnicPortal portal) {
        return portal.sourceName().equalsIgnoreCase(t.getSource());
    }

    private static boolean sameDepartment(String authority, String orgChain) {
        if (authority == null || orgChain == null) return false;
        String a = authority.toLowerCase(Locale.ROOT);
        for (String part : orgChain.split("\\|\\|")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.length() >= 6 && (a.contains(p) || p.contains(a))) return true;
        }
        return false;
    }

    Tender buildTender(GepnicPortal portal, GepnicParser.TenderRow row, Map<String, String> detail, String reason) {
        Tender t = new Tender();
        t.setId((portal.idPrefix() + row.tenderId()).toLowerCase(Locale.ROOT));
        if (portal.isGepnic()) {
            // GePNIC ids (2026_DHS_1352679_1) are globally unique, so they double as the reference number.
            t.setRefNo(row.tenderId());
            t.setSourceId(row.tenderId());
        } else {
            // Other systems use bare numbers; keep them portal-prefixed so they never match another source.
            t.setRefNo(firstNonBlank(row.referenceNumber(), listSourceId(portal, row.tenderId())));
            t.setSourceId(listSourceId(portal, row.tenderId()));
        }
        t.setEprocurementPortalId(row.tenderId());
        t.setOfficialReferenceNumber(firstNonBlank(detail.get("Tender Reference Number"), row.referenceNumber()));
        t.setPortalDocRef(detail.get(PORTAL_DOC_REF));
        t.setSource(portal.sourceName());
        t.setSector(portal.host());

        String title = firstNonBlank(detail.get("Work Description"), detail.get("Title"), row.title());
        t.setTitle(title);
        t.setProductNameAsPerTender(title);
        t.setAuthority(row.organisationChain().replace("||", " | "));

        String place = firstNonBlank(detail.get("Location"), lastSegment(row.organisationChain()));
        t.setPlace(place);
        t.setState(portal.state());
        t.setLocation(place != null ? place + ", " + portal.state() : portal.state());

        Double emd = GepnicParser.parseAmount(detail.get("EMD Amount"));
        t.setEmd(emd);
        t.setEmdRaw(emd != null ? "₹" + detail.get("EMD Amount") : "N/A");
        Double fee = GepnicParser.parseAmount(detail.get("Tender Fee"));
        t.setDocumentFee(fee);
        t.setDocumentFeeRaw(fee != null ? "₹" + detail.get("Tender Fee") : "N/A");
        Double value = GepnicParser.parseAmount(detail.get("Tender Value"));
        t.setEstimatedCost(value);
        t.setEstimatedCostRaw(value != null ? "₹" + detail.get("Tender Value") : "N/A");

        t.setPublishDate(row.publishedAt() != null ? row.publishedAt().toLocalDate().toString() : null);
        t.setStartDate(format(firstNonNull(GepnicParser.parseDateTime(detail.get("Bid Submission Start Date")), row.publishedAt())));
        t.setDueDate(format(firstNonNull(GepnicParser.parseDateTime(detail.get("Bid Submission End Date")), row.closingAt())));
        t.setOpeningDate(format(firstNonNull(GepnicParser.parseDateTime(detail.get("Bid Opening Date")), row.openingAt())));
        String preBid = detail.get("Pre Bid Meeting Date");
        t.setPreBidDate(preBid != null && !preBid.isBlank() && !"NA".equalsIgnoreCase(preBid) ? preBid : "No");

        // Portal links are tied to a browsing session and expire; store the stable portal entry page.
        t.setOriginalUrl(portal.entryUrl());
        String classification = TenderClassifier.classify(row.tenderId(), portal.baseUrl(), portal.host(), title);
        // Every configured portal is a government eProcurement system (nprocure.com is Gujarat's).
        t.setTenderType("Unverified".equals(classification) ? "Non GeM" : classification);
        t.setStatus("Issued");
        t.setScrapedAt(new Date().toString());
        t.setEntryDate(LocalDate.now(IST).format(ENTRY_DATE));
        t.setMisExecutive("");
        String vertical = TenderKeywords.verticalFor(title);
        t.setVerticalName(vertical);
        t.setProductNameAsPerMarken(vertical);
        t.setTime("N/A");
        t.setCorrigendumRemark("No");
        t.setBidQty(1);
        t.setQuotedQty(1);
        t.setNotes(buildNotes(portal, row, detail, reason));
        return t;
    }

    /** Fills gaps on a record that another source (usually Tender247) created first. */
    void enrich(Tender t, GepnicPortal portal, GepnicParser.TenderRow row, Map<String, String> detail) {
        if (isBlank(t.getEprocurementPortalId())) t.setEprocurementPortalId(row.tenderId());
        if (isBlank(t.getOfficialReferenceNumber())) {
            t.setOfficialReferenceNumber(firstNonBlank(detail.get("Tender Reference Number"), row.referenceNumber()));
        }
        if (TenderClassifier.isPlaceholderRefNo(t.getRefNo())) t.setRefNo(row.tenderId());
        if (isBlank(t.getState()) || "N/A".equals(t.getState())) t.setState(portal.state());
        if (t.getEmd() == null) {
            Double emd = GepnicParser.parseAmount(detail.get("EMD Amount"));
            if (emd != null) {
                t.setEmd(emd);
                t.setEmdRaw("₹" + detail.get("EMD Amount"));
            }
        }
        if (t.getDocumentFee() == null) {
            Double fee = GepnicParser.parseAmount(detail.get("Tender Fee"));
            if (fee != null) {
                t.setDocumentFee(fee);
                t.setDocumentFeeRaw("₹" + detail.get("Tender Fee"));
            }
        }
        if (isBlank(t.getDueDate()) || "N/A".equals(t.getDueDate())) {
            t.setDueDate(format(firstNonNull(GepnicParser.parseDateTime(detail.get("Bid Submission End Date")), row.closingAt())));
        }
        String marker = "[Matched on " + portal.name() + " portal as Tender ID " + row.tenderId() + "]";
        String notes = t.getNotes() == null ? "" : t.getNotes();
        if (!notes.contains(marker)) t.setNotes(notes.isBlank() ? marker : notes + " " + marker);
    }

    private static String buildNotes(GepnicPortal portal, GepnicParser.TenderRow row, Map<String, String> detail, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("Imported automatically from the ").append(portal.name()).append(" eProcurement portal (")
                .append(portal.host()).append(") matching ").append(reason).append(". ")
                .append("Search Tender ID ").append(row.tenderId()).append(" on the portal to open it.");
        appendIfPresent(sb, "Tender category", detail.get("Tender Category"));
        appendIfPresent(sb, "Product category", detail.get("Product Category"));
        appendIfPresent(sb, "Form of contract", detail.get("Form Of Contract"));
        appendIfPresent(sb, "Pincode", detail.get("Pincode"));
        appendIfPresent(sb, "Period of work (days)", detail.get("Period Of Work(Days)"));
        appendIfPresent(sb, "Bid validity (days)", detail.get("Bid Validity(Days)"));
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank() && !"NA".equalsIgnoreCase(value)) {
            sb.append(' ').append(label).append(": ").append(value).append('.');
        }
    }

    private List<GepnicPortal> loadPortals() {
        try (InputStream in = new ClassPathResource("state-portals.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            List<GepnicPortal> list = new ArrayList<>();
            for (JsonNode n : root.path("portals")) {
                list.add(objectMapper.treeToValue(n, GepnicPortal.class));
            }
            return List.copyOf(list);
        } catch (Exception e) {
            System.err.println("[StateSync] Could not load state-portals.json: " + e.getMessage());
            return List.of();
        }
    }

    private static String format(LocalDateTime dt) {
        return dt == null ? null : dt.format(DB_DATE_TIME);
    }

    private static String lastSegment(String orgChain) {
        if (orgChain == null) return null;
        String[] parts = orgChain.split("\\|\\|");
        return parts[parts.length - 1].trim();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank() && !"NA".equalsIgnoreCase(v.trim())) return v.trim();
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Outcome of one portal in one run. */
    public static class PortalResult {
        public final String portal;
        public int organisations;
        public int rowsSeen;
        public int rowsInWindow;
        public int relevant;
        public int detailsFetched;
        public int imported;
        public int enriched;
        public int alreadyKnown;
        public int skippedOverCap;
        public int failures;
        public int requests;
        public long durationMs;
        public String error;
        public final List<String> sample = new ArrayList<>();

        PortalResult(String portal) {
            this.portal = portal;
        }
    }

    /** Outcome of one run across portals. */
    public static class RunSummary {
        public final LocalDateTime startedAt;
        public final boolean dryRun;
        public volatile LocalDateTime finishedAt;
        public final List<PortalResult> portals = Collections.synchronizedList(new ArrayList<>());

        RunSummary(LocalDateTime startedAt, boolean dryRun) {
            this.startedAt = startedAt;
            this.dryRun = dryRun;
        }

        public int totalImported() {
            synchronized (portals) { return portals.stream().mapToInt(p -> p.imported).sum(); }
        }

        public int totalEnriched() {
            synchronized (portals) { return portals.stream().mapToInt(p -> p.enriched).sum(); }
        }

        public int totalRelevant() {
            synchronized (portals) { return portals.stream().mapToInt(p -> p.relevant).sum(); }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startedAt", startedAt.toString());
            m.put("finishedAt", finishedAt != null ? finishedAt.toString() : null);
            m.put("dryRun", dryRun);
            m.put("imported", totalImported());
            m.put("enriched", totalEnriched());
            m.put("relevant", totalRelevant());
            List<Map<String, Object>> list = new ArrayList<>();
            synchronized (portals) {
                for (PortalResult p : portals) {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("portal", p.portal);
                    pm.put("organisations", p.organisations);
                    pm.put("rowsSeen", p.rowsSeen);
                    pm.put("rowsInWindow", p.rowsInWindow);
                    pm.put("relevant", p.relevant);
                    pm.put("imported", p.imported);
                    pm.put("enriched", p.enriched);
                    pm.put("alreadyKnown", p.alreadyKnown);
                    pm.put("skippedOverCap", p.skippedOverCap);
                    pm.put("failures", p.failures);
                    pm.put("requests", p.requests);
                    pm.put("seconds", p.durationMs / 1000.0);
                    pm.put("error", p.error);
                    pm.put("sample", p.sample);
                    list.add(pm);
                }
            }
            m.put("portals", list);
            return m;
        }
    }
}
