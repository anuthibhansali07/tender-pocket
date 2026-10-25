package com.tenderpocket.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Entity
@Table(name = "tender_workflow_states", indexes = {
    @Index(name = "idx_workflow_tender_id", columnList = "tender_id"),
    @Index(name = "idx_workflow_current_step", columnList = "current_step")
})
public class TenderWorkflowState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "tender_id", nullable = false, unique = true)
    private String tenderId;

    @Column(name = "current_step")
    private Integer currentStep = 1;

    @Column(name = "step_name")
    private String stepName = "EXECUTIVE_ASSIGNMENT";

    // Step 1: Executive Assignment
    @Column(name = "assigned_executive")
    private String assignedExecutive;

    @Column(name = "assigned_at")
    private String assignedAt;

    // Step 2: Technical Specification
    @Column(name = "spec_status")
    private String specStatus = "NOT_STARTED"; // NOT_STARTED, DRAFT, PENDING, APPROVED, REJECTED

    @Column(name = "spec_compliance_type")
    private String specComplianceType; // ALL_COMPLY, DEVIATIONS

    @Column(name = "final_tech_spec_filename")
    private String finalTechSpecFilename;

    @Column(name = "final_tech_spec_uploaded_by")
    private String finalTechSpecUploadedBy;

    @Column(name = "final_tech_spec_uploaded_at")
    private String finalTechSpecUploadedAt;

    @Column(name = "spec_clearance_remarks", columnDefinition = "TEXT")
    private String specClearanceRemarks;

    // Step 3: Executive Handover & Document Preparation
    @Column(name = "bid_package_status")
    private String bidPackageStatus = "NOT_STARTED"; // NOT_STARTED, GENERATED

    @Column(name = "bid_package_generated_at")
    private String bidPackageGeneratedAt;

    // Step 4: EMD Payment Step
    @Column(name = "emd_applicable")
    private Boolean emdApplicable = true;

    @Column(name = "emd_status")
    private String emdStatus = "PENDING"; // EXEMPT_SKIPPED, PENDING, APPROVED, REJECTED

    @Column(name = "emd_beneficiary_name")
    private String emdBeneficiaryName;

    @Column(name = "emd_amount")
    private Double emdAmount;

    @Column(name = "emd_payment_mode")
    private String emdPaymentMode;

    @Column(name = "emd_receipt_doc_path")
    private String emdReceiptDocPath;

    @Column(name = "emd_receipt_confidential")
    private Boolean emdReceiptConfidential = true;

    @Column(name = "emd_approved_by")
    private String emdApprovedBy;

    @Column(name = "emd_approved_at")
    private String emdApprovedAt;

    // Step 5: Dual Track (Parallel Pricing + Document Verification)
    // Track A: Commercial Pricing
    @Column(name = "track_a_pricing_status")
    private String trackAPricingStatus = "NOT_STARTED"; // NOT_STARTED, ROUTING_PENDING, TPC_PENDING, MIS_PENDING, APPROVED

    @Column(name = "pricing_route")
    private String pricingRoute; // "TPC" or "MIS"

    @Column(name = "tpc_purchase_price")
    private Double tpcPurchasePrice;

    @Column(name = "mis_final_price")
    private Double misFinalPrice;

    @Column(name = "pricing_go_ahead_by")
    private String pricingGoAheadBy;

    @Column(name = "pricing_go_ahead_at")
    private String pricingGoAheadAt;

    // Track B: Document Verification
    @Column(name = "track_b_doc_status")
    private String trackBDocStatus = "NOT_STARTED"; // NOT_STARTED, PENDING, APPROVED, CHANGES_REQUESTED

    @Column(name = "docs_verified_by")
    private String docsVerifiedBy;

    @Column(name = "docs_verified_at")
    private String docsVerifiedAt;

    @Column(name = "total_path_docs")
    private Integer totalPathDocs = 0;

    @Column(name = "verified_path_docs")
    private Integer verifiedPathDocs = 0;

    @Column(name = "doc_verification_remarks", columnDefinition = "TEXT")
    private String docVerificationRemarks;

    // Step 6: Final Submission
    @Column(name = "can_final_submit")
    private Boolean canFinalSubmit = false;

    @Column(name = "submitted_by")
    private String submittedBy;

    @Column(name = "submitted_at")
    private String submittedAt;

    @Column(name = "submission_ack_path")
    private String submissionAckPath;

    // Step 7: Outcome
    @Column(name = "outcome_status")
    private String outcomeStatus = "PENDING"; // PENDING, WON, LOST

    @Column(name = "loss_reason", columnDefinition = "TEXT")
    private String lossReason;

    // Timestamps
    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

    public TenderWorkflowState() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public TenderWorkflowState(String tenderId) {
        this();
        this.tenderId = tenderId;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenderId() { return tenderId; }
    public void setTenderId(String tenderId) { this.tenderId = tenderId; }

    public Integer getCurrentStep() { return currentStep; }
    public void setCurrentStep(Integer currentStep) { this.currentStep = currentStep; }

    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }

    public String getAssignedExecutive() { return assignedExecutive; }
    public void setAssignedExecutive(String assignedExecutive) { this.assignedExecutive = assignedExecutive; }

    public String getAssignedAt() { return assignedAt; }
    public void setAssignedAt(String assignedAt) { this.assignedAt = assignedAt; }

    public String getSpecStatus() { return specStatus; }
    public void setSpecStatus(String specStatus) { this.specStatus = specStatus; }

    public String getSpecComplianceType() { return specComplianceType; }
    public void setSpecComplianceType(String specComplianceType) { this.specComplianceType = specComplianceType; }

    public String getFinalTechSpecFilename() { return finalTechSpecFilename; }
    public void setFinalTechSpecFilename(String finalTechSpecFilename) { this.finalTechSpecFilename = finalTechSpecFilename; }

    public String getFinalTechSpecUploadedBy() { return finalTechSpecUploadedBy; }
    public void setFinalTechSpecUploadedBy(String finalTechSpecUploadedBy) { this.finalTechSpecUploadedBy = finalTechSpecUploadedBy; }

    public String getFinalTechSpecUploadedAt() { return finalTechSpecUploadedAt; }
    public void setFinalTechSpecUploadedAt(String finalTechSpecUploadedAt) { this.finalTechSpecUploadedAt = finalTechSpecUploadedAt; }

    public String getSpecClearanceRemarks() { return specClearanceRemarks; }
    public void setSpecClearanceRemarks(String specClearanceRemarks) { this.specClearanceRemarks = specClearanceRemarks; }

    public String getBidPackageStatus() { return bidPackageStatus; }
    public void setBidPackageStatus(String bidPackageStatus) { this.bidPackageStatus = bidPackageStatus; }

    public String getBidPackageGeneratedAt() { return bidPackageGeneratedAt; }
    public void setBidPackageGeneratedAt(String bidPackageGeneratedAt) { this.bidPackageGeneratedAt = bidPackageGeneratedAt; }

    public Boolean getEmdApplicable() { return emdApplicable != null && emdApplicable; }
    public void setEmdApplicable(Boolean emdApplicable) { this.emdApplicable = emdApplicable; }

    public String getEmdStatus() { return emdStatus; }
    public void setEmdStatus(String emdStatus) { this.emdStatus = emdStatus; }

    public String getEmdBeneficiaryName() { return emdBeneficiaryName; }
    public void setEmdBeneficiaryName(String emdBeneficiaryName) { this.emdBeneficiaryName = emdBeneficiaryName; }

    public Double getEmdAmount() { return emdAmount; }
    public void setEmdAmount(Double emdAmount) { this.emdAmount = emdAmount; }

    public String getEmdPaymentMode() { return emdPaymentMode; }
    public void setEmdPaymentMode(String emdPaymentMode) { this.emdPaymentMode = emdPaymentMode; }

    public String getEmdReceiptDocPath() { return emdReceiptDocPath; }
    public void setEmdReceiptDocPath(String emdReceiptDocPath) { this.emdReceiptDocPath = emdReceiptDocPath; }

    public Boolean getEmdReceiptConfidential() { return emdReceiptConfidential != null && emdReceiptConfidential; }
    public void setEmdReceiptConfidential(Boolean emdReceiptConfidential) { this.emdReceiptConfidential = emdReceiptConfidential; }

    public String getEmdApprovedBy() { return emdApprovedBy; }
    public void setEmdApprovedBy(String emdApprovedBy) { this.emdApprovedBy = emdApprovedBy; }

    public String getEmdApprovedAt() { return emdApprovedAt; }
    public void setEmdApprovedAt(String emdApprovedAt) { this.emdApprovedAt = emdApprovedAt; }

    public String getTrackAPricingStatus() { return trackAPricingStatus; }
    public void setTrackAPricingStatus(String trackAPricingStatus) { this.trackAPricingStatus = trackAPricingStatus; }

    public String getPricingRoute() { return pricingRoute; }
    public void setPricingRoute(String pricingRoute) { this.pricingRoute = pricingRoute; }

    public Double getTpcPurchasePrice() { return tpcPurchasePrice; }
    public void setTpcPurchasePrice(Double tpcPurchasePrice) { this.tpcPurchasePrice = tpcPurchasePrice; }

    public Double getMisFinalPrice() { return misFinalPrice; }
    public void setMisFinalPrice(Double misFinalPrice) { this.misFinalPrice = misFinalPrice; }

    public String getPricingGoAheadBy() { return pricingGoAheadBy; }
    public void setPricingGoAheadBy(String pricingGoAheadBy) { this.pricingGoAheadBy = pricingGoAheadBy; }

    public String getPricingGoAheadAt() { return pricingGoAheadAt; }
    public void setPricingGoAheadAt(String pricingGoAheadAt) { this.pricingGoAheadAt = pricingGoAheadAt; }

    public String getTrackBDocStatus() { return trackBDocStatus; }
    public void setTrackBDocStatus(String trackBDocStatus) { this.trackBDocStatus = trackBDocStatus; }

    public String getDocsVerifiedBy() { return docsVerifiedBy; }
    public void setDocsVerifiedBy(String docsVerifiedBy) { this.docsVerifiedBy = docsVerifiedBy; }

    public String getDocsVerifiedAt() { return docsVerifiedAt; }
    public void setDocsVerifiedAt(String docsVerifiedAt) { this.docsVerifiedAt = docsVerifiedAt; }

    public Integer getTotalPathDocs() { return totalPathDocs != null ? totalPathDocs : 0; }
    public void setTotalPathDocs(Integer totalPathDocs) { this.totalPathDocs = totalPathDocs; }

    public Integer getVerifiedPathDocs() { return verifiedPathDocs != null ? verifiedPathDocs : 0; }
    public void setVerifiedPathDocs(Integer verifiedPathDocs) { this.verifiedPathDocs = verifiedPathDocs; }

    public String getDocVerificationRemarks() { return docVerificationRemarks; }
    public void setDocVerificationRemarks(String docVerificationRemarks) { this.docVerificationRemarks = docVerificationRemarks; }

    public Boolean getCanFinalSubmit() { return canFinalSubmit != null && canFinalSubmit; }
    public void setCanFinalSubmit(Boolean canFinalSubmit) { this.canFinalSubmit = canFinalSubmit; }

    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }

    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String submittedAt) { this.submittedAt = submittedAt; }

    public String getSubmissionAckPath() { return submissionAckPath; }
    public void setSubmissionAckPath(String submissionAckPath) { this.submissionAckPath = submissionAckPath; }

    public String getOutcomeStatus() { return outcomeStatus; }
    public void setOutcomeStatus(String outcomeStatus) { this.outcomeStatus = outcomeStatus; }

    public String getLossReason() { return lossReason; }
    public void setLossReason(String lossReason) { this.lossReason = lossReason; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
