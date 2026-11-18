package com.tenderpocket.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A Tender247 alert email that has been imported. The alert inbox is shared with people who read
 * these emails, so the import cannot use "unread" to find new ones; it skips the emails listed
 * here instead. Rows older than a week are removed (the import only looks at the last 2 days).
 */
@Entity
@Table(name = "processed_alert_email")
public class ProcessedAlertEmail {

    /** SHA-256 of the email's Message-ID (or of sender + subject + date when it has none). */
    @Id
    @Column(name = "email_key", length = 64)
    private String emailKey;

    @Column(length = 500)
    private String subject;

    private Integer tenders;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    protected ProcessedAlertEmail() {
    }

    public ProcessedAlertEmail(String emailKey, String subject, Integer tenders, LocalDateTime processedAt) {
        this.emailKey = emailKey;
        this.subject = subject;
        this.tenders = tenders;
        this.processedAt = processedAt;
    }

    public String getEmailKey() {
        return emailKey;
    }

    public String getSubject() {
        return subject;
    }

    public Integer getTenders() {
        return tenders;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }
}
