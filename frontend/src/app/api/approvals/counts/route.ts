import { NextResponse } from 'next/server';
import db from '@/lib/db';
import { reconcileApprovalRequests } from '@/lib/approvalsSync';
import { workflowActor } from '@/lib/workflowAuthorization';

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

    // 1. Try forwarding to backend if available and returns non-zero counts
    const backendUrl = process.env.BACKEND_URL || 'http://localhost:8090';
    if (backendUrl && backendUrl !== 'http://localhost:8080') {
      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 1500);
        const headers: Record<string, string> = { Accept: 'application/json' };
        const authH = request.headers.get('authorization');
        if (authH) headers['authorization'] = authH;

        const backendRes = await fetch(`${backendUrl}/api/approvals/counts`, {
          headers,
          signal: controller.signal,
          cache: 'no-store'
        });
        clearTimeout(timeout);

        if (backendRes.ok) {
          const cType = backendRes.headers.get('content-type') || '';
          if (cType.includes('application/json')) {
            const data = await backendRes.json();
            if (data?.counts?.TOTAL > 0) {
              return NextResponse.json(data);
            }
          }
        }
      } catch (err) {
        // Backend not reachable, fall back to SQLite
      }
    }

    // 2. Query SQLite
    let rows: { stage: string; count: number }[] = [];
    if (auth.role === 'Admin') {
      rows = db.prepare(
        "SELECT stage, COUNT(*) as count FROM tender_approval_requests WHERE status = 'PENDING' GROUP BY stage"
      ).all() as { stage: string; count: number }[];
    } else if (auth.role === 'MIS Team') {
      rows = db.prepare(
        `SELECT stage, COUNT(*) as count FROM tender_approval_requests 
         WHERE status = 'PENDING' AND (
           LOWER(assigned_to) = LOWER(?) 
           OR LOWER(assigned_to) = 'misteam' 
           OR LOWER(assigned_to) = 'mis team' 
           OR assigned_to IS NULL 
           OR stage IN ('SPEC_CLEARANCE', 'TPC_PRICING', 'MIS_PRICING', 'PAYMENT_APPROVAL', 'DOC_VERIFICATION', 'SUBMISSION_PENDING', 'WIN_LOSS_PENDING')
         ) GROUP BY stage`
      ).all(auth.username) as { stage: string; count: number }[];
    } else if (auth.role === 'Specification Team' || auth.role === 'Clearance Team') {
      rows = db.prepare(
        `SELECT stage, COUNT(*) as count FROM tender_approval_requests 
         WHERE status = 'PENDING' AND (
           LOWER(assigned_to) = LOWER(?)
           OR LOWER(assigned_to) = 'specification team'
           OR LOWER(assigned_to) = 'clearance'
           OR LOWER(assigned_to) = 'clearance team'
           OR stage = 'SPEC_CLEARANCE'
         ) GROUP BY stage`
      ).all(auth.username) as { stage: string; count: number }[];
    } else if (auth.role === 'TPC Team' || auth.role === 'TPC Pricing Team') {
      rows = db.prepare(
        `SELECT stage, COUNT(*) as count FROM tender_approval_requests 
         WHERE status = 'PENDING' AND (
           LOWER(assigned_to) = LOWER(?)
           OR LOWER(assigned_to) = 'tpc'
           OR LOWER(assigned_to) = 'tpc team'
           OR LOWER(assigned_to) = 'tpc pricing team'
           OR stage = 'TPC_PRICING'
         ) GROUP BY stage`
      ).all(auth.username) as { stage: string; count: number }[];
    } else if (auth.role === 'MIS Executive' || auth.role === 'Tender Executive' || auth.role === 'Executive') {
      rows = db.prepare(
        "SELECT stage, COUNT(*) as count FROM tender_approval_requests WHERE status = 'PENDING' AND (LOWER(requested_by) = LOWER(?) OR LOWER(assigned_to) = LOWER(?)) GROUP BY stage"
      ).all(auth.username, auth.username) as { stage: string; count: number }[];
    } else {
      rows = db.prepare(
        "SELECT stage, COUNT(*) as count FROM tender_approval_requests WHERE (LOWER(assigned_to) = LOWER(?) OR LOWER(assigned_to) = LOWER(?)) AND status = 'PENDING' GROUP BY stage"
      ).all(auth.username, auth.role) as { stage: string; count: number }[];
    }

    const counts: Record<string, number> = {
      TOTAL: 0,
      PAYMENT_APPROVAL: 0,
      DOC_VERIFICATION: 0,
      SUBMISSION_PENDING: 0,
      WIN_LOSS_PENDING: 0,
      SPEC_CLEARANCE: 0,
      TPC_PRICING: 0,
      MIS_PRICING: 0
    };

    let total = 0;
    for (const r of rows) {
      counts[r.stage] = r.count;
      total += r.count;
    }
    counts.TOTAL = total;

    return NextResponse.json({
      success: true,
      counts,
      username: auth.username,
      role: auth.role
    });

  } catch (error) {
    console.error('[api/approvals/counts] Error:', error);
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
