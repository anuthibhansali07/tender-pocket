/**
 * Centralized Tender Status and Pipeline Stage Resolution Engine
 * Ensures 100% calculation consistency across all API routes and frontend dashboards:
 * - /api/tenders
 * - /api/tenders/[id]
 * - /api/tenders/stats
 * - /api/analytics
 * - /api/approvals/*
 * - StatusDashboard
 * - Tender Detail Page (/tenders/[id])
 * - Main Dashboard Drawer (/page.tsx)
 */

export function getTodayISTString(): string {
  const options = { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit' };
  const formatter = new Intl.DateTimeFormat('en-IN', options as any);
  const parts = formatter.formatToParts(new Date());
  const day = parts.find(p => p.type === 'day')?.value || '01';
  const month = parts.find(p => p.type === 'month')?.value || '01';
  const year = parts.find(p => p.type === 'year')?.value || '2026';
  return `${year}-${month}-${day}`;
}

export function isLapsed(publishDateStr: string | null | undefined, todayISTStr: string): boolean {
  if (!publishDateStr || publishDateStr === 'N/A') return false;
  const date = new Date(publishDateStr);
  if (isNaN(date.getTime())) return false;
  
  const today = new Date(todayISTStr + 'T00:00:00+05:30');
  const publishDate = new Date(date);
  publishDate.setHours(0, 0, 0, 0);
  
  const diffTime = today.getTime() - publishDate.getTime();
  const diffDays = diffTime / (1000 * 60 * 60 * 24);
  return diffDays > 3;
}

export function resolveStatus(t: any, todayIST: string): string {
  if (!t) return 'New';
  const hasPassedDueDate = Boolean(t.due_date && typeof t.due_date === 'string' && t.due_date.trim() !== '' && t.due_date !== 'N/A' && t.due_date < todayIST);

  if (t.status === 'Awarded' || t.status === 'Won') return 'Won';
  if (t.status === 'Not Awarded' || t.status === 'Lost') return 'Lost';
  if (t.status === 'Filed' || t.status === 'Submitted') return 'Submitted';

  if (hasPassedDueDate) {
    if (t.status === 'Not Participating') {
      return 'Missed Opportunity';
    }
    // Only unreviewed / unacted bids become Missed Deadline
    if (t.status === 'Issued' || t.status === 'New' || !t.status) {
      return 'Missed Deadline';
    }
  }

  if (t.status === 'Not Participating') return 'Not Participating';
  if (t.status === 'Participating') return 'Participating';

  // Default or 'Issued' / 'New' status
  if (isLapsed(t.publish_date, todayIST)) {
    return 'Lapsed';
  }
  return 'New';
}

export interface PipelineStep {
  num: number;
  name: string;
  short: string;
}

export const PIPELINE_STEPS: PipelineStep[] = [
  { num: 1, name: 'Spec Clearance', short: 'Clearance' },
  { num: 2, name: 'TPC Pricing', short: 'TPC Quote' },
  { num: 3, name: 'MIS Pricing', short: 'MIS Price' },
  { num: 4, name: 'Docs Prep', short: 'Docs Prep' },
  { num: 5, name: 'Docs Approval', short: 'Verification' },
  { num: 6, name: 'EMD Payment', short: 'EMD Payment' },
  { num: 7, name: 'Submission', short: 'Portal File' },
  { num: 8, name: 'Outcome', short: 'Outcome' }
];

export interface TenderStageDetails {
  stageNumber: number; // 0 to 8
  stageKey: string;
  stageName: string;
  shortStage: string;
  actionTitle: string;
  actionDesc: string;
  statusColor: string;
  badgeBg: string;
  badgeBorder: string;
  needsAction: boolean;
  actionButtonText: string;
  stepCompleted: boolean[]; // Array of 8 booleans for steps 1..8
  currentStepIndex: number; // 0..7
  progressPercent: number; // 0..100 for line fill
  isWon: boolean;
  isLost: boolean;
  isSubmitted: boolean;
  isDeclined: boolean;
}

/**
 * Robust, Canonical Stage Resolver
 * Checks milestones from highest stage down to initial intake, eliminating premature short-circuits
 * caused by role confidentiality redactions or intermediate null fields.
 */
export function resolveTenderStageDetails(tender: any, userRole?: string | null): TenderStageDetails {
  if (!tender) {
    return {
      stageNumber: 0,
      stageKey: 'NEW_UNREVIEWED',
      stageName: 'Intake: New Assignment',
      shortStage: 'Decision Needed',
      actionTitle: 'Action: Review Tender',
      actionDesc: 'New tender assigned. Review requirements.',
      statusColor: '#f59e0b',
      badgeBg: 'rgba(245, 158, 11, 0.12)',
      badgeBorder: 'rgba(245, 158, 11, 0.3)',
      needsAction: true,
      actionButtonText: 'Review Bid',
      stepCompleted: [false, false, false, false, false, false, false, false],
      currentStepIndex: 0,
      progressPercent: 0,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  const role = (userRole || '').trim();
  const isClearanceRole = role === 'Clearance Team' || role === 'Specification Team';
  const isTpcRole = role === 'TPC Team' || role === 'TPC Pricing Team';
  const isMisRole = role === 'MIS Team' || role === 'Admin';

  // 1. Stage 8: Won
  if (
    tender.status === 'Won' || 
    tender.status === 'Awarded' || 
    tender.outcome_status === 'Won' || 
    tender.current_stage === 'WON'
  ) {
    return {
      stageNumber: 8,
      stageKey: 'WON',
      stageName: 'Won & Awarded',
      shortStage: 'Won / Awarded',
      actionTitle: 'Tender Won & Awarded',
      actionDesc: 'Bid awarded successfully. Contract processing active.',
      statusColor: '#10b981',
      badgeBg: 'rgba(16, 185, 129, 0.12)',
      badgeBorder: 'rgba(16, 185, 129, 0.3)',
      needsAction: false,
      actionButtonText: 'View Award',
      stepCompleted: [true, true, true, true, true, true, true, true],
      currentStepIndex: 7,
      progressPercent: 100,
      isWon: true,
      isLost: false,
      isSubmitted: true,
      isDeclined: false
    };
  }

  // 2. Stage 8: Lost
  if (
    tender.status === 'Lost' || 
    tender.status === 'Not Awarded' || 
    tender.outcome_status === 'Lost' || 
    tender.current_stage === 'LOST'
  ) {
    return {
      stageNumber: 8,
      stageKey: 'LOST',
      stageName: 'Lost / Closed',
      shortStage: 'Lost / Closed',
      actionTitle: 'Tender Lost',
      actionDesc: tender.loss_reason ? `Loss reason: ${tender.loss_reason}` : 'Bid concluded without contract award.',
      statusColor: '#ef4444',
      badgeBg: 'rgba(239, 68, 68, 0.12)',
      badgeBorder: 'rgba(239, 68, 68, 0.3)',
      needsAction: false,
      actionButtonText: 'View Details',
      stepCompleted: [true, true, true, true, true, true, true, true],
      currentStepIndex: 7,
      progressPercent: 100,
      isWon: false,
      isLost: true,
      isSubmitted: true,
      isDeclined: false
    };
  }

  // 3. Declined (Not Participating)
  if (tender.status === 'Not Participating') {
    return {
      stageNumber: 0,
      stageKey: 'NOT_PARTICIPATING',
      stageName: 'Participation Declined',
      shortStage: 'Not Participating',
      actionTitle: 'Participation Declined',
      actionDesc: 'Tender marked as not participating. You can re-activate anytime.',
      statusColor: '#64748b',
      badgeBg: 'rgba(100, 116, 139, 0.12)',
      badgeBorder: 'rgba(100, 116, 139, 0.3)',
      needsAction: false,
      actionButtonText: 'Re-activate',
      stepCompleted: [false, false, false, false, false, false, false, false],
      currentStepIndex: 0,
      progressPercent: 0,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: true
    };
  }

  // 4. Stage 8: Submitted / Under Evaluation (Portal File Completed, Awaiting Outcome)
  if (
    tender.status === 'Submitted' || 
    tender.status === 'Filed' || 
    tender.submission_status === 'Approved' || 
    tender.outcome_status === 'Pending' || 
    tender.current_stage === 'WIN_LOSS_PENDING' || 
    tender.current_stage === 'SUBMITTED'
  ) {
    return {
      stageNumber: 8,
      stageKey: 'SUBMITTED',
      stageName: 'Bid Submitted',
      shortStage: 'Under Evaluation',
      actionTitle: 'Submitted to Portal',
      actionDesc: 'Bid filed on portal and verified by MIS Team. Awaiting commercial evaluation.',
      statusColor: '#3b82f6',
      badgeBg: 'rgba(59, 130, 246, 0.12)',
      badgeBorder: 'rgba(59, 130, 246, 0.3)',
      needsAction: false,
      actionButtonText: 'View Submission',
      // Steps 1 to 7 are fully completed. Step 8 is awaiting commercial outcome.
      stepCompleted: [true, true, true, true, true, true, true, false],
      currentStepIndex: 7,
      progressPercent: 86, // Connects up to Step 7 (Submission)
      isWon: false,
      isLost: false,
      isSubmitted: true,
      isDeclined: false
    };
  }

  // 5. Stage 7: Submission Audit Pending
  if (tender.submission_status === 'Pending' || tender.current_stage === 'SUBMISSION_PENDING') {
    return {
      stageNumber: 7,
      stageKey: 'SUBMISSION_PENDING',
      stageName: 'Submission Audit',
      shortStage: 'Audit Pending',
      actionTitle: 'Awaiting Submission Audit',
      actionDesc: 'Bid filed. Awaiting MIS Team audit to verify submission.',
      statusColor: '#f59e0b',
      badgeBg: 'rgba(245, 158, 11, 0.12)',
      badgeBorder: 'rgba(245, 158, 11, 0.3)',
      needsAction: isMisRole,
      actionButtonText: isMisRole ? 'Audit Filing' : 'View Filing',
      stepCompleted: [true, true, true, true, true, true, false, false],
      currentStepIndex: 6,
      progressPercent: 71,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 6. Stage 7: Ready to Submit on Portal (EMD Payment approved)
  if (tender.payment_status === 'Approved' || tender.current_stage === 'READY_TO_SUBMIT') {
    return {
      stageNumber: 7,
      stageKey: 'READY_TO_SUBMIT',
      stageName: 'Portal Submission',
      shortStage: 'Ready to File',
      actionTitle: 'Action Required: File Bid on Portal',
      actionDesc: 'EMD Payment confirmed! Please physically/digitally file the bid on portal.',
      statusColor: '#8b5cf6',
      badgeBg: 'rgba(139, 92, 246, 0.15)',
      badgeBorder: 'rgba(139, 92, 246, 0.35)',
      needsAction: true,
      actionButtonText: 'File on Portal',
      stepCompleted: [true, true, true, true, true, true, false, false],
      currentStepIndex: 6,
      progressPercent: 71,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 7. Stage 6: EMD Payment Approval Pending
  if (tender.payment_status === 'Pending' || tender.current_stage === 'EMD_PENDING') {
    return {
      stageNumber: 6,
      stageKey: 'EMD_PENDING',
      stageName: 'EMD Payment Approval',
      shortStage: 'EMD In Progress',
      actionTitle: 'Awaiting EMD Payment Approval',
      actionDesc: 'EMD request submitted to MIS Team. Awaiting payment reference and approval.',
      statusColor: '#f59e0b',
      badgeBg: 'rgba(245, 158, 11, 0.12)',
      badgeBorder: 'rgba(245, 158, 11, 0.3)',
      needsAction: isMisRole,
      actionButtonText: isMisRole ? 'Approve EMD' : 'View EMD',
      stepCompleted: [true, true, true, true, true, false, false, false],
      currentStepIndex: 5,
      progressPercent: 57,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 8. Stage 6: EMD Payment Request Prep (Docs verified)
  if (
    tender.verification_status === 'Approved' || 
    tender.current_stage === 'EMD_REQ_READY' || 
    tender.current_stage === 'PAYMENT_APPROVAL'
  ) {
    return {
      stageNumber: 6,
      stageKey: 'EMD_REQ_READY',
      stageName: 'EMD Payment Prep',
      shortStage: 'Request EMD',
      actionTitle: 'Action: Submit EMD Request',
      actionDesc: 'Bid documents approved by MIS Team. Please submit EMD payment request.',
      statusColor: '#3b82f6',
      badgeBg: 'rgba(59, 130, 246, 0.15)',
      badgeBorder: 'rgba(59, 130, 246, 0.35)',
      needsAction: true,
      actionButtonText: 'Pay EMD',
      stepCompleted: [true, true, true, true, true, false, false, false],
      currentStepIndex: 5,
      progressPercent: 57,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 9. Stage 5: Document Verification Pending
  if (tender.verification_status === 'Pending' || tender.current_stage === 'DOC_VERIFICATION') {
    return {
      stageNumber: 5,
      stageKey: 'DOC_VERIFICATION',
      stageName: 'Bid Documents Verification',
      shortStage: 'Doc Review',
      actionTitle: 'Awaiting MIS Document Approval',
      actionDesc: 'Generated bid documents submitted. Awaiting MIS Team audit & sign-off.',
      statusColor: '#f59e0b',
      badgeBg: 'rgba(245, 158, 11, 0.12)',
      badgeBorder: 'rgba(245, 158, 11, 0.3)',
      needsAction: isMisRole,
      actionButtonText: isMisRole ? 'Audit Docs' : 'View Docs',
      stepCompleted: [true, true, true, true, false, false, false, false],
      currentStepIndex: 4,
      progressPercent: 43,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 10. Stage 4: Bid Documents Revision (Rejected)
  if (tender.verification_status === 'Rejected' || tender.current_stage === 'DOCS_REJECTED') {
    return {
      stageNumber: 4,
      stageKey: 'DOCS_REJECTED',
      stageName: 'Bid Documents Revision',
      shortStage: 'Docs Rejected',
      actionTitle: 'Action: Revise Bid Documents',
      actionDesc: 'MIS Team requested changes to bid documents. Regenerate and resubmit.',
      statusColor: '#ef4444',
      badgeBg: 'rgba(239, 68, 68, 0.15)',
      badgeBorder: 'rgba(239, 68, 68, 0.35)',
      needsAction: true,
      actionButtonText: 'Revise Docs',
      stepCompleted: [true, true, true, false, false, false, false, false],
      currentStepIndex: 3,
      progressPercent: 29,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 11. Stage 4: Bid Documents Preparation (MIS final price configured)
  if (
    (tender.mis_final_price && Number(tender.mis_final_price) > 0) || 
    tender.current_stage === 'BID_DOC_PENDING' || 
    tender.current_stage === 'DOCS_PREP'
  ) {
    return {
      stageNumber: 4,
      stageKey: 'DOCS_PREP',
      stageName: 'Bid Documents Preparation',
      shortStage: 'Generate Docs',
      actionTitle: 'Action: Generate Bid Documents',
      actionDesc: 'MIS Provided Price configured. Fill the bid form and generate Annexure docs.',
      statusColor: '#10b981',
      badgeBg: 'rgba(16, 185, 129, 0.15)',
      badgeBorder: 'rgba(16, 185, 129, 0.35)',
      needsAction: true,
      actionButtonText: 'Generate Docs',
      stepCompleted: [true, true, true, false, false, false, false, false],
      currentStepIndex: 3,
      progressPercent: 29,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 12. Stage 3: MIS Pricing (TPC price submitted, awaiting MIS final price)
  const hasTpcPriceEntered = Boolean(
    tender.has_tpc_price || 
    (tender.tpc_purchase_price && Number(tender.tpc_purchase_price) > 0) || 
    tender.current_stage === 'MIS_PRICING'
  );

  if (hasTpcPriceEntered) {
    return {
      stageNumber: 3,
      stageKey: 'MIS_PRICING',
      stageName: 'MIS Pricing',
      shortStage: 'MIS Pricing',
      actionTitle: 'Awaiting MIS Team Pricing',
      actionDesc: 'Manufacturer quote submitted by TPC Team. Awaiting MIS Team provided price.',
      statusColor: '#f59e0b',
      badgeBg: 'rgba(245, 158, 11, 0.12)',
      badgeBorder: 'rgba(245, 158, 11, 0.3)',
      needsAction: isMisRole,
      actionButtonText: isMisRole ? 'Set Price' : 'View Quote',
      stepCompleted: [true, true, false, false, false, false, false, false],
      currentStepIndex: 2,
      progressPercent: 14,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 13. Stage 2: TPC Pricing (Specs approved, awaiting TPC quote)
  if (tender.spec_verification_status === 'Approved' || tender.current_stage === 'TPC_PRICING') {
    return {
      stageNumber: 2,
      stageKey: 'TPC_PRICING',
      stageName: 'TPC Pricing',
      shortStage: 'TPC Quote',
      actionTitle: 'Awaiting TPC Pricing',
      actionDesc: 'Technical specs approved. Awaiting OEM purchase price from TPC Team.',
      statusColor: '#ec4899',
      badgeBg: 'rgba(236, 72, 153, 0.12)',
      badgeBorder: 'rgba(236, 72, 153, 0.3)',
      needsAction: isTpcRole || role === 'Admin',
      actionButtonText: isTpcRole ? 'Set OEM Price' : 'View Status',
      stepCompleted: [true, false, false, false, false, false, false, false],
      currentStepIndex: 1,
      progressPercent: 0,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 14. Stage 1: Spec Clearance Pending
  if (tender.spec_verification_status === 'Pending' || tender.current_stage === 'SPEC_CLEARANCE') {
    return {
      stageNumber: 1,
      stageKey: 'SPEC_CLEARANCE_PENDING',
      stageName: 'Spec Clearance Pending',
      shortStage: 'Clearance Pending',
      actionTitle: 'Awaiting Clearance Team Approval',
      actionDesc: 'Technical specs submitted. Awaiting Clearance Team verification and sign-off.',
      statusColor: '#8b5cf6',
      badgeBg: 'rgba(139, 92, 246, 0.12)',
      badgeBorder: 'rgba(139, 92, 246, 0.3)',
      needsAction: isClearanceRole || role === 'Admin',
      actionButtonText: isClearanceRole ? 'Review Specs' : 'View Status',
      stepCompleted: [false, false, false, false, false, false, false, false],
      currentStepIndex: 0,
      progressPercent: 0,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 15. Stage 1: Spec Rejected
  if (tender.spec_verification_status === 'Rejected') {
    return {
      stageNumber: 1,
      stageKey: 'SPEC_REJECTED',
      stageName: 'Technical Specs Rejected',
      shortStage: 'Specs Rejected',
      actionTitle: 'Action: Re-upload Technical Specs',
      actionDesc: 'Clearance Team rejected technical specification. Upload revised document.',
      statusColor: '#ef4444',
      badgeBg: 'rgba(239, 68, 68, 0.15)',
      badgeBorder: 'rgba(239, 68, 68, 0.35)',
      needsAction: true,
      actionButtonText: 'Upload Documents',
      stepCompleted: [false, false, false, false, false, false, false, false],
      currentStepIndex: 0,
      progressPercent: 0,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 16. Stage 1: Participating, Specs Not Uploaded Yet
  if (tender.status === 'Participating') {
    return {
      stageNumber: 1,
      stageKey: 'SPEC_NOT_STARTED',
      stageName: 'Technical Specs Needed',
      shortStage: 'Upload Specs',
      actionTitle: 'Action: Upload Tech Spec Document',
      actionDesc: 'Bid accepted for participation. Upload tender PDF/Doc to generate technical specs.',
      statusColor: 'var(--primary)',
      badgeBg: 'rgba(99, 102, 241, 0.15)',
      badgeBorder: 'rgba(99, 102, 241, 0.35)',
      needsAction: true,
      actionButtonText: 'Upload Documents',
      stepCompleted: [false, false, false, false, false, false, false, false],
      currentStepIndex: 0,
      progressPercent: 0,
      isWon: false,
      isLost: false,
      isSubmitted: false,
      isDeclined: false
    };
  }

  // 17. Stage 0: Intake / New Unreviewed Assignment
  return {
    stageNumber: 0,
    stageKey: 'NEW_UNREVIEWED',
    stageName: 'Intake: New Assignment',
    shortStage: 'Decision Needed',
    actionTitle: 'Action: Accept or Decline Bid',
    actionDesc: 'New tender assigned. Review requirements and decide whether to participate.',
    statusColor: 'var(--accent-yellow)',
    badgeBg: 'rgba(245, 158, 11, 0.15)',
    badgeBorder: 'rgba(245, 158, 11, 0.35)',
    needsAction: true,
    actionButtonText: 'Review Bid',
    stepCompleted: [false, false, false, false, false, false, false, false],
    currentStepIndex: 0,
    progressPercent: 0,
    isWon: false,
    isLost: false,
    isSubmitted: false,
    isDeclined: false
  };
}

export function resolveTenderStageKey(tender: any): string {
  return resolveTenderStageDetails(tender).stageKey;
}
