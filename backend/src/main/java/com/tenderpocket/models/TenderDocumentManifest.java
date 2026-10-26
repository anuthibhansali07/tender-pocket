package com.tenderpocket.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Entity
@Table(name = "tender_document_manifest", indexes = {
    @Index(name = "idx_manifest_tender_id", columnList = "tender_id"),
    @Index(name = "idx_manifest_category", columnList = "file_category")
})
public class TenderDocumentManifest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private String tenderId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "relative_path", columnDefinition = "TEXT", nullable = false)
    private String relativePath;

    @Column(name = "file_category", nullable = false)
    private String fileCategory; // ORIGINAL_NOTICE, FINAL_TECH_SPEC, BID_PACKAGE, EMD_RECEIPT, QUOTATION, OTHER

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes = 0L;

    @Column(name = "file_extension")
    private String fileExtension;

    @Column(name = "is_confidential")
    private Boolean isConfidential = false;

    @Column(name = "is_authoritative")
    private Boolean isAuthoritative = false;

    @Column(name = "verified_by_mis")
    private Boolean verifiedByMis = false;

    @Column(name = "verified_by_user")
    private String verifiedByUser;

    @Column(name = "verified_at")
    private String verifiedAt;

    @Column(name = "created_at")
    private String createdAt;

    public TenderDocumentManifest() {
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public TenderDocumentManifest(String tenderId, String fileName, String displayName, String relativePath, String fileCategory, Long fileSizeBytes, String fileExtension, Boolean isConfidential, Boolean isAuthoritative) {
        this();
        this.tenderId = tenderId;
        this.fileName = fileName;
        this.displayName = displayName;
        this.relativePath = relativePath;
        this.fileCategory = fileCategory;
        this.fileSizeBytes = fileSizeBytes;
        this.fileExtension = fileExtension;
        this.isConfidential = isConfidential;
        this.isAuthoritative = isAuthoritative;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenderId() { return tenderId; }
    public void setTenderId(String tenderId) { this.tenderId = tenderId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getDisplayName() { return displayName != null ? displayName : fileName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

    public String getFileCategory() { return fileCategory; }
    public void setFileCategory(String fileCategory) { this.fileCategory = fileCategory; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getFileExtension() { return fileExtension; }
    public void setFileExtension(String fileExtension) { this.fileExtension = fileExtension; }

    public Boolean getIsConfidential() { return isConfidential != null && isConfidential; }
    public void setIsConfidential(Boolean isConfidential) { this.isConfidential = isConfidential; }

    public Boolean getIsAuthoritative() { return isAuthoritative != null && isAuthoritative; }
    public void setIsAuthoritative(Boolean isAuthoritative) { this.isAuthoritative = isAuthoritative; }

    public Boolean getVerifiedByMis() { return verifiedByMis != null && verifiedByMis; }
    public void setVerifiedByMis(Boolean verifiedByMis) { this.verifiedByMis = verifiedByMis; }

    public String getVerifiedByUser() { return verifiedByUser; }
    public void setVerifiedByUser(String verifiedByUser) { this.verifiedByUser = verifiedByUser; }

    public String getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(String verifiedAt) { this.verifiedAt = verifiedAt; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
