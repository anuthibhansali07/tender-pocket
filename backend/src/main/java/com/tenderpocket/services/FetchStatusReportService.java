package com.tenderpocket.services;

import com.tenderpocket.models.PortalFetchStatus;
import com.tenderpocket.scheduler.SyncRetryTracker;
import com.tenderpocket.services.state.GepnicPortal;
import com.tenderpocket.services.state.StateTenderSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the "Tender Fetch Status" report for the MIS Team: one entry per source (GeM, Tender247, the central
 * portal, each enabled state/UT portal) saying when it was last read and whether tenders from it
 * might be missing. No error text or internal details are included.
 */
@Service
public class FetchStatusReportService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<String> UNION_TERRITORIES = Set.of("AN", "CH", "DN", "DD", "DL", "JK", "LD", "LA", "PY");
    static final String GEM_URL = "https://bidplus.gem.gov.in/all-bids";
    static final String TENDER247_URL = "https://www.tender247.com/";

    @Autowired
    private FetchStatusRecorder recorder;

    @Autowired(required = false)
    private StateTenderSyncService stateTenderSyncService;

    @Autowired(required = false)
    private SyncRetryTracker retryTracker;

    public Map<String, Object> buildReport() {
        Map<String, PortalFetchStatus> stored = recorder.findAll().stream()
                .collect(Collectors.toMap(PortalFetchStatus::getSource, Function.identity(), (a, b) -> a));

        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(entry(FetchStatusRecorder.GEM, "GeM (Government e-Marketplace)", "gem", GEM_URL, stored));
        sources.add(entry(FetchStatusRecorder.TENDER247, "Tender247 (email alerts)", "tender247", TENDER247_URL, stored));
        if (stateTenderSyncService != null) {
            for (GepnicPortal p : stateTenderSyncService.getPortals()) {
                if (!p.enabled()) continue;
                String kind = "CENTRAL".equals(p.code()) ? "central"
                        : UNION_TERRITORIES.contains(p.code()) ? "ut" : "state";
                sources.add(entry(p.code(), p.name(), kind, p.entryUrl(), stored));
            }
        }

        List<String> order = List.of("failed", "not_set_up", "partial", "not_checked", "ok");
        List<String> kindOrder = List.of("gem", "tender247", "central", "state", "ut");
        sources.sort(Comparator
                .comparingInt((Map<String, Object> m) -> order.indexOf((String) m.get("status")))
                .thenComparingInt(m -> kindOrder.indexOf((String) m.get("kind")))
                .thenComparing(m -> (String) m.get("name")));

        Map<String, Long> counts = sources.stream()
                .collect(Collectors.groupingBy(m -> (String) m.get("status"), Collectors.counting()));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", sources.size());
        summary.put("ok", counts.getOrDefault("ok", 0L));
        summary.put("partial", counts.getOrDefault("partial", 0L));
        summary.put("failed", counts.getOrDefault("failed", 0L));
        summary.put("notChecked", counts.getOrDefault("not_checked", 0L));
        summary.put("notSetUp", counts.getOrDefault("not_set_up", 0L));
        summary.put("checkingNow", sources.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("checkingNow"))));
        summary.put("lastUpdatedAt", stored.values().stream()
                .map(PortalFetchStatus::getLastAttemptAt).filter(t -> t != null)
                .max(Comparator.naturalOrder()).map(FetchStatusReportService::iso).orElse(null));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("generatedAt", iso(LocalDateTime.now(IST)));
        body.put("summary", summary);
        body.put("sources", sources);
        return body;
    }

    private Map<String, Object> entry(String key, String name, String kind, String url, Map<String, PortalFetchStatus> stored) {
        PortalFetchStatus s = stored.get(key);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("kind", kind);
        m.put("portalUrl", url);
        m.put("status", s == null || s.getOutcome() == null ? "not_checked" : s.getOutcome().toLowerCase());
        m.put("checkingNow", recorder.isInProgress(key));
        m.put("lastCheckedAt", s != null ? iso(s.getLastAttemptAt()) : null);
        m.put("lastCompleteAt", s != null ? iso(s.getLastCompleteAt()) : null);
        m.put("lastReachedAt", s != null ? iso(s.getLastReachedAt()) : null);
        m.put("newTenders", s != null ? s.getNewTenders() : null);
        m.put("sectionsMissed", s != null && s.getFailedSections() != null ? s.getFailedSections() : 0);
        m.put("missedProducts", s != null && s.getMissedKeywords() != null
                ? Arrays.stream(s.getMissedKeywords().split(",")).map(String::trim).filter(k -> !k.isEmpty()).toList()
                : List.of());
        m.put("nextAttemptAt", retryTracker != null
                ? retryTracker.find(key).map(r -> r.dueAt.toString()).orElse(null) : null);
        return m;
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.atZone(IST).toOffsetDateTime().toString();
    }
}
