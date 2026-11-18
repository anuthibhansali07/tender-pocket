package com.tenderpocket.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Latest outcome of importing tenders from one source (GeM, the central portal, or a state/UT
 * portal), kept so the MIS Team can see which tenders they might be missing.
 */
@Entity
@Table(name = "portal_fetch_status")
public class PortalFetchStatus {

    public static final String OK = "OK";
    /** The portal was read but some of its department lists (or GeM product searches) failed. */
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";
    /** The source can't be read because it was never set up (Tender247 with no alert inbox). */
    public static final String NOT_SET_UP = "NOT_SET_UP";

    /** "GEM" or the portal code from state-portals.json (CENTRAL, MH...). */
    @Id
    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    /** Last time a fetch finished, whatever its outcome (IST). */
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    /** Last time the source was read in full (outcome OK, IST). Tenders after this may be missing. */
    @Column(name = "last_complete_at")
    private LocalDateTime lastCompleteAt;

    /** Last time the source could be read at all (OK or PARTIAL, IST). */
    @Column(name = "last_reached_at")
    private LocalDateTime lastReachedAt;

    /** New tenders added by the latest fetch. */
    @Column(name = "new_tenders")
    private Integer newTenders;

    /** Department lists that could not be opened in the latest fetch (state portals). */
    @Column(name = "failed_sections")
    private Integer failedSections;

    /** GeM product searches that did not finish, comma separated. */
    @Column(name = "missed_keywords", columnDefinition = "TEXT")
    private String missedKeywords;

    public PortalFetchStatus() {
    }

    public PortalFetchStatus(String source) {
        this.source = source;
    }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public LocalDateTime getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(LocalDateTime lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public LocalDateTime getLastCompleteAt() { return lastCompleteAt; }
    public void setLastCompleteAt(LocalDateTime lastCompleteAt) { this.lastCompleteAt = lastCompleteAt; }
    public LocalDateTime getLastReachedAt() { return lastReachedAt; }
    public void setLastReachedAt(LocalDateTime lastReachedAt) { this.lastReachedAt = lastReachedAt; }
    public Integer getNewTenders() { return newTenders; }
    public void setNewTenders(Integer newTenders) { this.newTenders = newTenders; }
    public Integer getFailedSections() { return failedSections; }
    public void setFailedSections(Integer failedSections) { this.failedSections = failedSections; }
    public String getMissedKeywords() { return missedKeywords; }
    public void setMissedKeywords(String missedKeywords) { this.missedKeywords = missedKeywords; }
}
