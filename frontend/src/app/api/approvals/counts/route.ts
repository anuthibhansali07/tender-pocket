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

    const { searchParams } = new URL(request.url);
    const statusParam = (searchParams.get('status') || 'PENDING').toUpperCase();
    const isAllStatus = statusParam === 'ALL';
    const isHistory = statusParam === 'HISTORY';
    const statusClause = isHistory ? "status != 'PENDING'" : (isAllStatus ? "1=1" : "status = ?");

    // 2. Query SQLite
    let rows: { stage: string; count: number }[] = [];
    if (auth.role === 'Admin') {
      const adminParams = (isAllStatus || isHistory) ? [] : [statusParam];
      rows = db.prepare(
        `SELECT stage, COUNT(*) as count FROM tender_approval_requests WHERE ${statusClause} GROUP BY stage`
      ).all(...adminParams) as { stage: string; count: number }[];
    } else if (auth.role === 'MIS Team') {
      const misParams = (isAllStatus || isHistory) ? [auth.username, auth.username] : [statusParam, auth.username, auth.username];
      rows = db.prepare(
        `SELECT stage, COUNT(*) as count FROM tender_approval_requests 
         WHERE ${statusClause} AND (
           LOWER(assigned_to) = LOWER(?) 
           OR LOWER(assigned_to) = 'misteam' 
           OR LOWER(assigned_to) = 'mis team' 
           OR assigned_to IS NULL 
           OR stage IN ('SPEC_CLEARANCE', 'TPC_PRICING', 'MIS_PRICING', 'PAYMENT_APPROVAL', 'DOC_VERIFICATION', 'SUBMISSION_PENDING', 'WIN_LOSS_PENDING')
           OR LOWER(requested_by) = LOWER(?)
         ) GROUP BY stage`
      ).all(...misParams) as { stage: string; count: number }[];
    } else if (auth.role === 'Specification Team' || auth.role === 'Clearance Team') {
      const clearanceParams = (isAllStatus || isHistory) ? [auth.username, auth.username] : [statusParam, auth.username, auth.username];
      rows = db.prepare(
        `SELECT stage, COUNT(*) as count FROM tender_approval_requests 
         WHERE ${statusClause} AND (
           LOWER(assigned_to) = LOWER(?)
           OR LOWER(assigned_to) = 'specification team'
           OR LOWER(assigned_to) = 'clearance'
           OR LOWER(assigned_to) = 'clearance team'
           OR stage = 'SPEC_CLEARANCE'
           OR LOWER(requested_by) = LOWER(?)
         ) GROUP BY stage`
      ).all(...clearanceParams) as { stage: string; count: number }[];
    } else if (auth.role === 'TPC Team' || auth.role === 'TPC Pricing Team') {
      const tpcParams = (isAllStatus || isHistory) ? [auth.username, auth.username] : [statusParam, auth.username, auth.username];
      rows = db.prepare(
        `SELECT stage, COUNT(*) as count FROM tender_approval_requests 
         WHERE ${statusClause} AND (
           LOWER(assigned_to) = LOWER(?)
           OR LOWER(assigned_to) = 'tpc'
           OR LOWER(assigned_to) = 'tpc team'
           OR LOWER(assigned_to) = 'tpc pricing team'
           OR stage = 'TPC_PRICING'
           OR LOWER(requested_by) = LOWER(?)
         ) GROUP BY stage`
      ).all(...tpcParams) as { stage: string; count: number }[];
    } else if (auth.role === 'MIS Executive' || auth.role === 'Tender Executive' || auth.role === 'Executive') {
      const execParams = (isAllStatus || isHistory) ? [auth.username, auth.username] : [statusParam, auth.username, auth.username];
      rows = db.prepare(
        `SELECT stage, COUNT(*) as count FROM tender_approval_requests WHERE ${statusClause} AND (LOWER(requested_by) = LOWER(?) OR LOWER(assigned_to) = LOWER(?)) GROUP BY stage`
      ).all(...execParams) as { stage: string; count: number }[];
    } else {
      const defParams = (isAllStatus || isHistory) ? [auth.username, auth.role] : [statusParam, auth.username, auth.role];
      rows = db.prepare(
        `SELECT stage, COUNT(*) as count FROM tender_approval_requests WHERE (LOWER(assigned_to) = LOWER(?) OR LOWER(assigned_to) = LOWER(?)) AND ${statusClause} GROUP BY stage`
      ).all(...defParams) as { stage: string; count: number }[];
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

    // Status counts for tabs (Pending vs History)
    let statusQuery = "SELECT status, COUNT(*) as count FROM tender_approval_requests";
    const statusParams: any[] = [];
    if (auth.role === 'Admin') {
      statusQuery += " GROUP BY status";
    } else if (auth.role === 'MIS Team') {
      statusQuery += ` WHERE (
        LOWER(assigned_to) = LOWER(?) 
        OR LOWER(assigned_to) = 'misteam' 
        OR LOWER(assigned_to) = 'mis team' 
        OR assigned_to IS NULL 
        OR stage IN ('SPEC_CLEARANCE', 'TPC_PRICING', 'MIS_PRICING', 'PAYMENT_APPROVAL', 'DOC_VERIFICATION', 'SUBMISSION_PENDING', 'WIN_LOSS_PENDING')
        OR LOWER(requested_by) = LOWER(?)
      ) GROUP BY status`;
      statusParams.push(auth.username, auth.username);
    } else if (auth.role === 'Specification Team' || auth.role === 'Clearance Team') {
      statusQuery += ` WHERE (
        LOWER(assigned_to) = LOWER(?)
        OR LOWER(assigned_to) = 'specification team'
        OR LOWER(assigned_to) = 'clearance'
        OR LOWER(assigned_to) = 'clearance team'
        OR stage = 'SPEC_CLEARANCE'
        OR LOWER(requested_by) = LOWER(?)
      ) GROUP BY status`;
      statusParams.push(auth.username, auth.username);
    } else if (auth.role === 'TPC Team' || auth.role === 'TPC Pricing Team') {
      statusQuery += ` WHERE (
        LOWER(assigned_to) = LOWER(?)
        OR LOWER(assigned_to) = 'tpc'
        OR LOWER(assigned_to) = 'tpc team'
        OR LOWER(assigned_to) = 'tpc pricing team'
        OR stage = 'TPC_PRICING'
        OR LOWER(requested_by) = LOWER(?)
      ) GROUP BY status`;
      statusParams.push(auth.username, auth.username);
    } else if (auth.role === 'MIS Executive' || auth.role === 'Tender Executive' || auth.role === 'Executive') {
      statusQuery += " WHERE (LOWER(requested_by) = LOWER(?) OR LOWER(assigned_to) = LOWER(?)) GROUP BY status";
      statusParams.push(auth.username, auth.username);
    } else {
      statusQuery += " WHERE (LOWER(assigned_to) = LOWER(?) OR LOWER(assigned_to) = LOWER(?)) GROUP BY status";
      statusParams.push(auth.username, auth.role);
    }

    const statusRows = db.prepare(statusQuery).all(...statusParams) as { status: string; count: number }[];
    const statusCounts: Record<string, number> = {
      TOTAL: 0,
      PENDING: 0,
      APPROVED: 0,
      REJECTED: 0,
      CHANGES_REQUESTED: 0,
      HISTORY: 0
    };
    let totalStatus = 0;
    for (const sr of statusRows) {
      if (sr.status) {
        statusCounts[sr.status] = sr.count;
        totalStatus += sr.count;
      }
    }
    statusCounts.HISTORY = (statusCounts.APPROVED || 0) + (statusCounts.REJECTED || 0) + (statusCounts.CHANGES_REQUESTED || 0);
    statusCounts.TOTAL = totalStatus;

    // Stage counts partitioned by Pending vs History for Admin / UI
    const pendingRows = db.prepare(
      `SELECT stage, COUNT(*) as count FROM tender_approval_requests WHERE status = 'PENDING' GROUP BY stage`
    ).all() as { stage: string; count: number }[];
    const pendingCounts: Record<string, number> = {
      TOTAL: 0,
      PAYMENT_APPROVAL: 0,
      DOC_VERIFICATION: 0,
      SUBMISSION_PENDING: 0,
      WIN_LOSS_PENDING: 0,
      SPEC_CLEARANCE: 0,
      TPC_PRICING: 0,
      MIS_PRICING: 0
    };
    let pendingTotal = 0;
    for (const r of pendingRows) {
      pendingCounts[r.stage] = r.count;
      pendingTotal += r.count;
    }
    pendingCounts.TOTAL = pendingTotal;

    const historyRows = db.prepare(
      `SELECT stage, COUNT(*) as count FROM tender_approval_requests WHERE status != 'PENDING' GROUP BY stage`
    ).all() as { stage: string; count: number }[];
    const historyCounts: Record<string, number> = {
      TOTAL: 0,
      PAYMENT_APPROVAL: 0,
      DOC_VERIFICATION: 0,
      SUBMISSION_PENDING: 0,
      WIN_LOSS_PENDING: 0,
      SPEC_CLEARANCE: 0,
      TPC_PRICING: 0,
      MIS_PRICING: 0
    };
    let historyTotal = 0;
    for (const r of historyRows) {
      historyCounts[r.stage] = r.count;
      historyTotal += r.count;
    }
    historyCounts.TOTAL = historyTotal;

    // Team and role breakdowns
    const teamRows = db.prepare(
      `SELECT LOWER(COALESCE(assigned_to, 'unassigned')) as team, COUNT(*) as count FROM tender_approval_requests GROUP BY LOWER(COALESCE(assigned_to, 'unassigned'))`
    ).all() as { team: string; count: number }[];
    const teamCounts: Record<string, number> = {};
    for (const tr of teamRows) {
      teamCounts[tr.team] = tr.count;
    }

    const roleRows = db.prepare(
      `SELECT LOWER(COALESCE(requested_by, 'unknown')) as role, COUNT(*) as count FROM tender_approval_requests GROUP BY LOWER(COALESCE(requested_by, 'unknown'))`
    ).all() as { role: string; count: number }[];
    const roleCounts: Record<string, number> = {};
    for (const rr of roleRows) {
      roleCounts[rr.role] = rr.count;
    }

    return NextResponse.json({
      success: true,
      counts,
      pendingCounts,
      historyCounts,
      statusCounts,
      teamCounts,
      roleCounts,
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
