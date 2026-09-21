import { NextResponse } from 'next/server';
import db from '@/lib/db';
import { reconcileApprovalRequests } from '@/lib/approvalsSync';
import { workflowActor, redactManufacturerPricing } from '@/lib/workflowAuthorization';

export async function GET(request: Request) {
  try {
    const auth = workflowActor(request, 'viewTenders');
    const allowedRoles = [
      'Admin', 'MIS Team', 'MIS Executive',
      'Clearance Team', 'Specification Team',
      'TPC Pricing Team', 'TPC Team',
      'Tender Executive', 'Executive'
    ];
    if (!auth || !allowedRoles.includes(auth.role)) {
      return NextResponse.json(
        { success: false, error: 'Access denied: Authorized team role or Admin required' },
        { status: 403 }
      );
    }

    // Always ensure SQLite approval requests are in 100% sync with tenders
    reconcileApprovalRequests();

    const { searchParams } = new URL(request.url);
    const stage = searchParams.get('stage') || '';
    const search = searchParams.get('search') || '';
    const team = searchParams.get('team') || '';
    const roleParam = searchParams.get('role') || '';

    const statusParam = (searchParams.get('status') || 'PENDING').toUpperCase();

    // 2. Query SQLite
    let query = `
      SELECT 
        r.id,
        r.tender_id as tenderId,
        r.stage,
        r.stage as stageName,
        r.requested_by as requestedBy,
        r.assigned_to as assignedTo,
        r.status,
        r.working_path as workingPath,
        r.emd_amount as emdAmount,
        r.transfer_mode as transferMode,
        r.transfer_ref_no as transferRefNo,
        r.receipt_file_url as receiptFileUrl,
        r.loss_reason_executive as lossReasonExecutive,
        r.loss_reason_mis as lossReasonMis,
        r.tpc_purchase_price as tpcPurchasePrice,
        r.mis_final_price as misFinalPrice,
        r.created_at as createdAt,
        r.updated_at as updatedAt,
        r.reviewed_by as reviewedBy,
        r.reviewer_comment as reviewerComment,
        t.ref_no as tenderRefNo,
        t.title as tenderTitle,
        t.authority as tenderAuthority,
        t.due_date as tenderDueDate,
        t.estimated_cost as tenderEstimatedCost,
        t.emd as tenderEmd,
        t.location as tenderLocation,
        t.sector as tenderSector,
        t.status as tenderStatus,
        t.current_stage as tenderCurrentStage,
        t.mis_executive as misExecutive
      FROM tender_approval_requests r
      LEFT JOIN tenders t ON r.tender_id = t.id
    `;

    const params: any[] = [];

    if (statusParam === 'HISTORY') {
      query += " WHERE r.status != 'PENDING'";
    } else if (statusParam !== 'ALL') {
      query += " WHERE r.status = ?";
      params.push(statusParam);
    } else {
      query += " WHERE 1=1";
    }

    if (auth.role === 'Admin') {
      // Admin sees all approvals according to status
    } else if (auth.role === 'MIS Team') {
      query += ` AND (
        LOWER(r.assigned_to) = LOWER(?) 
        OR LOWER(r.assigned_to) = 'misteam' 
        OR LOWER(r.assigned_to) = 'mis team' 
        OR r.assigned_to IS NULL 
        OR r.stage IN ('SPEC_CLEARANCE', 'TPC_PRICING', 'MIS_PRICING', 'PAYMENT_APPROVAL', 'DOC_VERIFICATION', 'SUBMISSION_PENDING', 'WIN_LOSS_PENDING')
        OR LOWER(r.requested_by) = LOWER(?)
      )`;
      params.push(auth.username, auth.username);
    } else if (auth.role === 'Specification Team' || auth.role === 'Clearance Team') {
      query += ` AND (
        LOWER(r.assigned_to) = LOWER(?)
        OR LOWER(r.assigned_to) = 'specification team'
        OR LOWER(r.assigned_to) = 'clearance'
        OR LOWER(r.assigned_to) = 'clearance team'
        OR r.stage = 'SPEC_CLEARANCE'
        OR LOWER(r.requested_by) = LOWER(?)
      )`;
      params.push(auth.username, auth.username);
    } else if (auth.role === 'TPC Team' || auth.role === 'TPC Pricing Team') {
      query += ` AND (
        LOWER(r.assigned_to) = LOWER(?)
        OR LOWER(r.assigned_to) = 'tpc'
        OR LOWER(r.assigned_to) = 'tpc team'
        OR LOWER(r.assigned_to) = 'tpc pricing team'
        OR r.stage = 'TPC_PRICING'
        OR LOWER(r.requested_by) = LOWER(?)
      )`;
      params.push(auth.username, auth.username);
    } else if (auth.role === 'MIS Executive' || auth.role === 'Tender Executive' || auth.role === 'Executive') {
      query += ' AND (LOWER(r.requested_by) = LOWER(?) OR LOWER(r.assigned_to) = LOWER(?))';
      params.push(auth.username, auth.username);
    } else {
      query += ' AND (LOWER(r.assigned_to) = LOWER(?) OR LOWER(r.assigned_to) = LOWER(?))';
      params.push(auth.username, auth.role);
    }

    if (stage && stage !== 'ALL') {
      query += ' AND r.stage = ?';
      params.push(stage);
    }

    if (team && team !== 'ALL') {
      query += ' AND LOWER(r.assigned_to) = LOWER(?)';
      params.push(team);
    }

    if (roleParam && roleParam !== 'ALL') {
      query += ' AND LOWER(r.requested_by) = LOWER(?)';
      params.push(roleParam);
    }

    if (search) {
      query += ' AND (t.title LIKE ? OR t.ref_no LIKE ? OR t.authority LIKE ? OR r.tender_id LIKE ? OR r.requested_by LIKE ?)';
      params.push(`%${search}%`, `%${search}%`, `%${search}%`, `%${search}%`, `%${search}%`);
    }

    query += ' ORDER BY r.updated_at DESC, r.created_at DESC';

    const approvals = db.prepare(query).all(...params);

    // Security rule: Redact TPC manufacturer purchase price for executives
    if (auth.role === 'Tender Executive' || auth.role === 'MIS Executive' || auth.role === 'Executive') {
      for (const a of (approvals as any[])) {
        a.tpcPurchasePrice = null;
      }
    }

    return NextResponse.json({
      success: true,
      approvals: redactManufacturerPricing(approvals, auth.role),
      total: approvals.length
    });

  } catch (error) {
    console.error('[api/approvals/pending] Error:', error);
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
