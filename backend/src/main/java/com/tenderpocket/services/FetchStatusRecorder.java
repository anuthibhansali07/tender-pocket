package com.tenderpocket.services;

import com.tenderpocket.models.PortalFetchStatus;
import com.tenderpocket.repositories.PortalFetchStatusRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records the outcome of every tender import (scheduled, retried or started by hand), one row per
 * source, for the "Tender Fetch Status" page. Recording never breaks an import: errors are logged.
 */
@Service
public class FetchStatusRecorder {

    public static final String GEM = "GEM";
    public static final String TENDER247 = "TENDER247";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired(required = false)
    private PortalFetchStatusRepository repository;

    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    public void markStarted(Collection<String> sources) {
        inProgress.addAll(sources);
    }

    public void markFinished(Collection<String> sources) {
        inProgress.removeAll(sources);
    }

    public boolean isInProgress(String source) {
        return inProgress.contains(source);
    }

    public Optional<PortalFetchStatus> find(String source) {
        if (repository == null) return Optional.empty();
        try {
            return repository.findById(source);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<PortalFetchStatus> findAll() {
        if (repository == null) return List.of();
        try {
            return repository.findAll();
        } catch (Exception e) {
            System.err.println("[FetchStatus] Could not read fetch status: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Records a state or central portal fetch.
     *
     * @param error          whole-portal failure, or null when the portal was read
     * @param failedSections department lists that could not be opened
     */
    public void recordPortal(String code, int newTenders, int failedSections, String error) {
        String outcome = error != null ? PortalFetchStatus.FAILED
                : failedSections > 0 ? PortalFetchStatus.PARTIAL : PortalFetchStatus.OK;
        save(code, outcome, error != null ? null : newTenders, failedSections, null);
    }

    /**
     * Records a GeM fetch.
     *
     * @param searched       keywords searched; empty means the full keyword list
     * @param failedKeywords keywords that did not finish
     * @param crashed        true when the whole run failed before finishing
     */
    public void recordGem(List<String> searched, List<String> failedKeywords, int newTenders, boolean crashed) {
        Set<String> missed = new LinkedHashSet<>();
        PortalFetchStatus previous = find(GEM).orElse(null);
        if (searched != null && !searched.isEmpty() && previous != null && previous.getMissedKeywords() != null) {
            // A partial (retry) run only settles the keywords it searched.
            for (String k : previous.getMissedKeywords().split(",")) {
                if (!k.isBlank() && !searched.contains(k.trim())) missed.add(k.trim());
            }
        }
        if (crashed) {
            if (searched != null) missed.addAll(searched);
        } else if (failedKeywords != null) {
            missed.addAll(failedKeywords);
        }
        boolean wholeRunFailed = crashed && (searched == null || searched.isEmpty());
        String outcome = wholeRunFailed ? PortalFetchStatus.FAILED
                : missed.isEmpty() ? PortalFetchStatus.OK : PortalFetchStatus.PARTIAL;
        save(GEM, outcome, crashed ? null : newTenders, 0, missed.isEmpty() ? null : String.join(",", missed));
    }

    /**
     * Records a Tender247 alert-email import.
     *
     * @param configured   false when no alert inbox is set up, so nothing could be read
     * @param failedEmails alert emails that could not be read (left unread for the next run)
     * @param crashed      true when the inbox could not be opened at all
     */
    public void recordTender247(boolean configured, int newTenders, int failedEmails, boolean crashed) {
        String outcome = !configured ? PortalFetchStatus.NOT_SET_UP
                : crashed ? PortalFetchStatus.FAILED
                : failedEmails > 0 ? PortalFetchStatus.PARTIAL : PortalFetchStatus.OK;
        boolean read = configured && !crashed;
        save(TENDER247, outcome, read ? newTenders : null, read ? failedEmails : 0, null);
    }

    private void save(String source, String outcome, Integer newTenders, int failedSections, String missedKeywords) {
        if (repository == null) return;
        try {
            LocalDateTime now = LocalDateTime.now(IST);
            PortalFetchStatus s = repository.findById(source).orElseGet(() -> new PortalFetchStatus(source));
            s.setOutcome(outcome);
            s.setLastAttemptAt(now);
            if (PortalFetchStatus.OK.equals(outcome) || PortalFetchStatus.PARTIAL.equals(outcome)) s.setLastReachedAt(now);
            if (PortalFetchStatus.OK.equals(outcome)) s.setLastCompleteAt(now);
            if (newTenders != null) s.setNewTenders(newTenders);
            s.setFailedSections(failedSections);
            s.setMissedKeywords(missedKeywords);
            repository.save(s);
        } catch (Exception e) {
            System.err.println("[FetchStatus] Could not record fetch status for " + source + ": " + e.getMessage());
        }
    }
}
