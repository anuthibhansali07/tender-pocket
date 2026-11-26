package com.tenderpocket.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Records that a one-time sync job has run, so it does not run again on the next start. */
@Entity
@Table(name = "sync_markers")
public class SyncMarker {

    @Id
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt = LocalDateTime.now();

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    public SyncMarker() {}

    public SyncMarker(String name, String details) {
        this.name = name;
        this.details = details;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
}
