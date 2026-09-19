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

    // 1. Try forwarding to backend if available and has records
    const backendUrl = process.env.BACKEND_URL || 'http://localhost:8090';
    if (backendUrl && backendUrl !== 'http://localhost:8080') {
      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 2000);
        const headers: Record<string, string> = { Accept: 'application/json' };
        const authH = request.headers.get('authorization');
        if (authH) headers['authorization'] = authH;

        const backendRes = await fetch(`${backendUrl}/api/approvals/pending?${searchParams.toString()}`, {
          headers,
          signal: controller.signal,
          cache: 'no-store'
        });
        clearTimeout(timeout);

        if (backendRes.ok) {
          const cType = backendRes.headers.get('content-type') || '';
          if (cType.includes('application/json')) {
            const data = await backendRes.json();
            if (data && Array.isArray(data.approvals) && data.approvals.length > 0) {
              data.approvals = data.approvals.map((a: any) => ({
                id: a.id,
                tenderId: a.tenderId || a.tender_id || '',
                stage: a.stage || '',
                stageName: a.stageName || a.stage_name || a.stage || '',
                requestedBy: a.requestedBy || a.requested_by || 'Unknown',
                assignedTo: a.assignedTo || a.assigned_to || '',
                status: a.status || 'PENDING',
                workingPath: a.workingPath || a.working_path || null,
                emdAmount: a.emdAmount != null ? a.emdAmount : a.emd_amount,
                transferMode: a.transferMode || a.transfer_mode || null,
                transferRefNo: a.transferRefNo || a.transfer_ref_no || null,
                receiptFileUrl: a.receiptFileUrl || a.receipt_file_url || null,
                lossReasonExecutive: a.lossReasonExecutive || a.loss_reason_executive || null,
                lossReasonMis: a.lossReasonMis || a.loss_reason_mis || null,
                tpcPurchasePrice: a.tpcPurchasePrice != null ? a.tpcPurchasePrice : a.tpc_purchase_price,
                misFinalPrice: a.misFinalPrice != null ? a.misFinalPrice : a.mis_final_price,
                createdAt: a.createdAt || a.created_at,
                updatedAt: a.updatedAt || a.updated_at,
                tenderRefNo: a.tenderRefNo || a.tender_ref_no || '',
                tenderTitle: a.tenderTitle || a.tender_title || a.tenderId || a.tender_id || '',
                tenderAuthority: a.tenderAuthority || a.tender_authority || '',
                tenderDueDate: a.tenderDueDate || a.tender_due_date || '',
                tenderEstimatedCost: a.tenderEstimatedCost != null ? a.tenderEstimatedCost : a.tender_estimated_cost,
                tenderEmd: a.tenderEmd != null ? a.tenderEmd : a.tender_emd,
                tenderLocation: a.tenderLocation || a.tender_location || '',
                tenderSector: a.tenderSector || a.tender_sector || '',
                tenderStatus: a.tenderStatus || a.tender_status || '',
                tenderCurrentStage: a.tenderCurrentStage || a.tender_current_stage || '',
                misExecutive: a.misExecutive || a.mis_executive || ''
              }));
              return NextResponse.json(redactManufacturerPricing(data, auth.role));
            }
            // If backend returned empty list [], do NOT return empty if SQLite has pending items
          }
        }
      } catch (err) {
        // Backend not reachable, fall back to SQLite
      }
    }

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
        t.ref_no as tenderRefNo,
        t.title as tenderTitle,
        t.authority as tenderAuthority,
        t.due_date as tenderDueDate,
        t.estimated_cost as tenderEstimatedCost,
        t.emd as tenderEmd,
        t.location as tenderLocation,
        t.sector as tenderSector,
        t.status as tenderStatus,
        t.mis_executive as misExecutive
      FROM tender_approval_requests r
      LEFT JOIN tenders t ON r.tender_id = t.id
      WHERE r.status = 'PENDING'
    `;

    const params: any[] = [];

    if (auth.role === 'Admin') {
      // Admin sees all pending
    } else if (auth.role === 'MIS Team') {
      query += ` AND (
        LOWER(r.assigned_to) = LOWER(?) 
        OR LOWER(r.assigned_to) = 'misteam' 
        OR LOWER(r.assigned_to) = 'mis team' 
        OR r.assigned_to IS NULL 
        OR r.stage IN ('SPEC_CLEARANCE', 'TPC_PRICING', 'MIS_PRICING', 'PAYMENT_APPROVAL', 'DOC_VERIFICATION', 'SUBMISSION_PENDING', 'WIN_LOSS_PENDING')
      )`;
      params.push(auth.username);
    } else if (auth.role === 'Specification Team' || auth.role === 'Clearance Team') {
      // Specification Team & Clearance Team see SPEC_CLEARANCE stage items
      query += ` AND (
        LOWER(r.assigned_to) = LOWER(?)
        OR LOWER(r.assigned_to) = 'specification team'
        OR LOWER(r.assigned_to) = 'clearance'
        OR LOWER(r.assigned_to) = 'clearance team'
        OR r.stage = 'SPEC_CLEARANCE'
      )`;
      params.push(auth.username);
    } else if (auth.role === 'TPC Team' || auth.role === 'TPC Pricing Team') {
      // TPC Team sees TPC_PRICING stage items
      query += ` AND (
        LOWER(r.assigned_to) = LOWER(?)
        OR LOWER(r.assigned_to) = 'tpc'
        OR LOWER(r.assigned_to) = 'tpc team'
        OR LOWER(r.assigned_to) = 'tpc pricing team'
        OR r.stage = 'TPC_PRICING'
      )`;
      params.push(auth.username);
    } else if (auth.role === 'MIS Executive' || auth.role === 'Tender Executive' || auth.role === 'Executive') {
      // MIS / Tender Executives see approvals they requested or assigned to them
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

    if (search) {
      query += ' AND (t.title LIKE ? OR t.ref_no LIKE ? OR t.authority LIKE ? OR r.tender_id LIKE ? OR r.requested_by LIKE ?)';
      params.push(`%${search}%`, `%${search}%`, `%${search}%`, `%${search}%`, `%${search}%`);
    }

    query += ' ORDER BY r.created_at DESC';

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
