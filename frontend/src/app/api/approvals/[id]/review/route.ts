import { NextResponse } from 'next/server';
import db from '@/lib/db';
import { getAuthFromRequest } from '@/lib/auth';
import { reconcileApprovalRequests } from '@/lib/approvalsSync';

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const auth = getAuthFromRequest(request);
    const allowedRoles = [
      'Admin', 'MIS Team', 'MIS Executive',
      'Clearance Team', 'Specification Team',
      'TPC Pricing Team', 'TPC Team'
    ];
    if (!auth || !allowedRoles.includes(auth.role)) {
      return NextResponse.json(
        { success: false, error: 'Access denied: Authorized team role or Admin required' },
        { status: 403 }
      );
    }

    const { id: approvalIdStr } = await params;
    const approvalId = parseInt(approvalIdStr, 10);
    if (isNaN(approvalId)) {
      return NextResponse.json(
        { success: false, error: 'Invalid approval request ID' },
        { status: 400 }
      );
    }

    const body = await request.json();
    const action = (body.action || '').toUpperCase();
    const outcome = body.outcome;
    const comment = (body.comment || '').trim();
    const lossReasonMis = (body.lossReasonMis || '').trim();
    const tpcPurchasePrice = body.tpcPurchasePrice ? Number(body.tpcPurchasePrice) : null;
    const misFinalPrice = body.misFinalPrice ? Number(body.misFinalPrice) : null;

    if (!['APPROVED', 'REJECTED', 'CHANGES_REQUESTED'].includes(action)) {
      return NextResponse.json(
        { success: false, error: 'Invalid action. Must be APPROVED, REJECTED, or CHANGES_REQUESTED' },
        { status: 400 }
      );
    }

    if (!comment) {
      return NextResponse.json(
        { success: false, error: 'A reviewer comment is required' },
        { status: 400 }
      );
    }

    // ── Resilient Approval Request Lookup ───────────────────────────────────
    // 1. Try finding by primary key ID
    let approvalReq = db.prepare('SELECT * FROM tender_approval_requests WHERE id = ?').get(approvalId) as any;

    // 2. Fallback: look up by tender_id + stage (prioritizing PENDING request)
    if (!approvalReq && (body.tenderId || body.stage)) {
      if (body.tenderId && body.stage) {
        approvalReq = db.prepare(`
          SELECT * FROM tender_approval_requests 
          WHERE tender_id = ? AND stage = ? 
          ORDER BY (CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END), id DESC LIMIT 1
        `).get(body.tenderId, body.stage) as any;
      } else if (body.tenderId) {
        approvalReq = db.prepare(`
          SELECT * FROM tender_approval_requests 
          WHERE tender_id = ? 
          ORDER BY (CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END), id DESC LIMIT 1
        `).get(body.tenderId) as any;
      }
    }

    // 3. Fallback: if not found by ID or tender_id, but tender exists, synthesize/find the request
    let tender: any = null;
    const resolvedTenderId = approvalReq?.tender_id || body.tenderId;
    if (resolvedTenderId) {
      tender = db.prepare('SELECT * FROM tenders WHERE id = ?').get(resolvedTenderId) as any;
    }

    if (!approvalReq && tender && body.stage) {
      const nowIso = new Date().toISOString();
      const insertResult = db.prepare(`
        INSERT INTO tender_approval_requests (tender_id, stage, requested_by, assigned_to, status, created_at, updated_at)
        VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
      `).run(tender.id, body.stage, auth.username, 'misteam', nowIso, nowIso);
      approvalReq = db.prepare('SELECT * FROM tender_approval_requests WHERE id = ?').get(insertResult.lastInsertRowid) as any;
    }

    if (!approvalReq) {
      return NextResponse.json(
        { success: false, error: 'Approval request not found' },
        { status: 404 }
      );
    }

    // ── Role Authorization Check ─────────────────────────────────────────────
    if (auth.role !== 'Admin') {
      const stage = approvalReq.stage;
      const isMisStage = ['MIS_PRICING', 'DOC_VERIFICATION', 'PAYMENT_APPROVAL', 'SUBMISSION_PENDING', 'WIN_LOSS_PENDING'].includes(stage);
      const isSpecStage = stage === 'SPEC_CLEARANCE';
      const isTpcStage = stage === 'TPC_PRICING';

      const isMisTeamMember = auth.role === 'MIS Team' || auth.role === 'MIS Executive';
      const isSpecTeamMember = auth.role === 'Specification Team' || auth.role === 'Clearance Team';
      const isTpcTeamMember = auth.role === 'TPC Team' || auth.role === 'TPC Pricing Team';

      if (isMisStage && !isMisTeamMember) {
        return NextResponse.json(
          { success: false, error: 'Access denied: MIS Team or Admin required' },
          { status: 403 }
        );
      }
      if (isSpecStage && !isSpecTeamMember) {
        return NextResponse.json(
          { success: false, error: 'Access denied: Clearance Team or Admin required' },
          { status: 403 }
        );
      }
      if (isTpcStage && !isTpcTeamMember) {
        return NextResponse.json(
          { success: false, error: 'Access denied: TPC Team or Admin required' },
          { status: 403 }
        );
      }
    }

    // ── Ensure Fresh Tender Reference ────────────────────────────────────────
    if (!tender) {
      tender = db.prepare('SELECT * FROM tenders WHERE id = ?').get(approvalReq.tender_id) as any;
    }

    // If request was already approved, check if tender is truly approved or still needs advancing
    if (approvalReq.status !== 'PENDING' && action === 'APPROVED' && tender) {
      let alreadyFullyProcessed = false;
      if (approvalReq.stage === 'DOC_VERIFICATION' && tender.verification_status === 'Approved' && tender.current_stage !== 'DOC_VERIFICATION') {
        alreadyFullyProcessed = true;
      } else if (approvalReq.stage === 'PAYMENT_APPROVAL' && tender.payment_status === 'Approved' && tender.current_stage !== 'PAYMENT_APPROVAL') {
        alreadyFullyProcessed = true;
      } else if (approvalReq.stage === 'SPEC_CLEARANCE' && tender.spec_verification_status === 'Approved' && tender.current_stage !== 'SPEC_CLEARANCE') {
        alreadyFullyProcessed = true;
      } else if (approvalReq.stage === 'SUBMISSION_PENDING' && tender.submission_status === 'Approved' && tender.current_stage !== 'SUBMISSION_PENDING') {
        alreadyFullyProcessed = true;
      } else if (approvalReq.stage === 'WIN_LOSS_PENDING' && (tender.outcome_status === 'Won' || tender.outcome_status === 'Lost')) {
        alreadyFullyProcessed = true;
      }

      if (alreadyFullyProcessed) {
        return NextResponse.json({
          success: true,
          message: 'This approval request has already been completed.'
        });
      }
    }

    const now = new Date().toISOString();

    // ── Primary SQLite Transaction ───────────────────────────────────────────
    const updateTx = db.transaction(() => {
      // 1. Update the approval request row
      db.prepare(`
        UPDATE tender_approval_requests 
        SET status = ?, loss_reason_mis = ?, updated_at = ?
        WHERE id = ?
      `).run(action, lossReasonMis || null, now, approvalReq.id);

      // 2. Record audit comment
      const commentText = `${action} by ${auth.username} (${auth.role}): ${comment}${lossReasonMis ? ` [MIS Loss Reason: ${lossReasonMis}]` : ''}`;
      db.prepare(`
        INSERT INTO tender_workflow_comments (tender_id, phase, author, author_role, comment, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
      `).run(approvalReq.tender_id, approvalReq.stage, auth.username, auth.role, commentText, now);

      // 3. Handle REJECTION / CHANGES REQUESTED
      if (action !== 'APPROVED' && tender) {
        if (approvalReq.stage === 'SPEC_CLEARANCE') {
          const specStatus = action === 'REJECTED' ? 'Rejected' : 'Changes Requested';
          db.prepare("UPDATE tenders SET spec_verification_status = ? WHERE id = ?").run(specStatus, approvalReq.tender_id);
        } else if (approvalReq.stage === 'DOC_VERIFICATION') {
          db.prepare("UPDATE tenders SET verification_status = 'Rejected' WHERE id = ?").run(approvalReq.tender_id);
        } else if (approvalReq.stage === 'PAYMENT_APPROVAL') {
          db.prepare("UPDATE tenders SET payment_status = 'Rejected' WHERE id = ?").run(approvalReq.tender_id);
        } else if (approvalReq.stage === 'SUBMISSION_PENDING') {
          db.prepare("UPDATE tenders SET submission_status = 'Rejected' WHERE id = ?").run(approvalReq.tender_id);
        } else if (approvalReq.stage === 'WIN_LOSS_PENDING') {
          db.prepare("UPDATE tenders SET outcome_status = 'Lost', status = 'Not Awarded', current_stage = 'LOST', loss_reason = COALESCE(?, loss_reason) WHERE id = ?")
            .run(lossReasonMis || 'Rejected by reviewer', approvalReq.tender_id);
        }
      }

      // 4. Handle APPROVAL (Sequential Stage Advancements)
      if (action === 'APPROVED' && tender) {
        if (approvalReq.stage === 'DOC_VERIFICATION') {
          // Advance tender to PAYMENT_APPROVAL & mark verification_status as Approved
          db.prepare(`
            UPDATE tenders 
            SET verification_status = 'Approved', 
                current_stage = 'PAYMENT_APPROVAL' 
            WHERE id = ?
          `).run(approvalReq.tender_id);

          // Ensure next pending approval request for PAYMENT_APPROVAL exists
          const existingPayment = db.prepare(
            "SELECT id FROM tender_approval_requests WHERE tender_id = ? AND stage = 'PAYMENT_APPROVAL' AND status = 'PENDING'"
          ).get(approvalReq.tender_id);

          if (!existingPayment) {
            db.prepare(`
              INSERT INTO tender_approval_requests (tender_id, stage, requested_by, assigned_to, emd_amount, transfer_mode, transfer_ref_no, status, created_at, updated_at)
              VALUES (?, 'PAYMENT_APPROVAL', ?, ?, ?, ?, ?, 'PENDING', ?, ?)
            `).run(
              approvalReq.tender_id,
              tender.mis_executive || auth.username || 'executive',
              tender.assigned_mis_member_emd || tender.assigned_mis_member || 'misteam',
              tender.emd_amount_actual || null,
              tender.emd_payment_mode || null,
              tender.emd_payment_ref || null,
              now,
              now
            );
          }
        } else if (approvalReq.stage === 'PAYMENT_APPROVAL') {
          // Advance tender to SUBMISSION_PENDING & mark payment_status as Approved
          db.prepare(`
            UPDATE tenders 
            SET payment_status = 'Approved', 
                current_stage = 'SUBMISSION_PENDING' 
            WHERE id = ?
          `).run(approvalReq.tender_id);

          // Ensure next pending approval request for SUBMISSION_PENDING exists
          const existingSub = db.prepare(
            "SELECT id FROM tender_approval_requests WHERE tender_id = ? AND stage = 'SUBMISSION_PENDING' AND status = 'PENDING'"
          ).get(approvalReq.tender_id);

          if (!existingSub) {
            db.prepare(`
              INSERT INTO tender_approval_requests (tender_id, stage, requested_by, assigned_to, status, created_at, updated_at)
              VALUES (?, 'SUBMISSION_PENDING', ?, ?, 'PENDING', ?, ?)
            `).run(
              approvalReq.tender_id,
              tender.mis_executive || auth.username || 'executive',
              tender.assigned_mis_member_submission || tender.assigned_mis_member || 'misteam',
              now,
              now
            );
          }
        } else if (approvalReq.stage === 'SPEC_CLEARANCE') {
          db.prepare(`
            UPDATE tenders 
            SET spec_verification_status = 'Approved', 
                current_stage = 'TPC_PRICING' 
            WHERE id = ?
          `).run(approvalReq.tender_id);

          const existingTpc = db.prepare(
            "SELECT id FROM tender_approval_requests WHERE tender_id = ? AND stage = 'TPC_PRICING' AND status = 'PENDING'"
          ).get(approvalReq.tender_id);

          if (!existingTpc) {
            db.prepare(`
              INSERT INTO tender_approval_requests (tender_id, stage, requested_by, assigned_to, status, created_at, updated_at)
              VALUES (?, 'TPC_PRICING', ?, 'tpc', 'PENDING', ?, ?)
            `).run(approvalReq.tender_id, auth.username || 'clearance', now, now);
          }
        } else if (approvalReq.stage === 'TPC_PRICING') {
          const price = tpcPurchasePrice || approvalReq.tpc_purchase_price;
          if (price) {
            db.prepare("UPDATE tenders SET tpc_purchase_price = ?, current_stage = 'MIS_PRICING' WHERE id = ?").run(price, approvalReq.tender_id);
            db.prepare("UPDATE tender_approval_requests SET tpc_purchase_price = ? WHERE id = ?").run(price, approvalReq.id);
          } else {
            db.prepare("UPDATE tenders SET current_stage = 'MIS_PRICING' WHERE id = ?").run(approvalReq.tender_id);
          }

          const existingMis = db.prepare(
            "SELECT id FROM tender_approval_requests WHERE tender_id = ? AND stage = 'MIS_PRICING' AND status = 'PENDING'"
          ).get(approvalReq.tender_id);

          if (!existingMis) {
            db.prepare(`
              INSERT INTO tender_approval_requests (tender_id, stage, requested_by, assigned_to, tpc_purchase_price, status, created_at, updated_at)
              VALUES (?, 'MIS_PRICING', ?, 'misteam', ?, 'PENDING', ?, ?)
            `).run(approvalReq.tender_id, auth.username || 'tpc', price || null, now, now);
          }
        } else if (approvalReq.stage === 'MIS_PRICING') {
          const price = misFinalPrice || approvalReq.mis_final_price;
          if (price) {
            db.prepare("UPDATE tenders SET mis_final_price = ?, current_stage = 'BID_DOC_PENDING' WHERE id = ?").run(price, approvalReq.tender_id);
            db.prepare("UPDATE tender_approval_requests SET mis_final_price = ? WHERE id = ?").run(price, approvalReq.id);
          } else {
            db.prepare("UPDATE tenders SET current_stage = 'BID_DOC_PENDING' WHERE id = ?").run(approvalReq.tender_id);
          }
        } else if (approvalReq.stage === 'SUBMISSION_PENDING') {
          db.prepare(`
            UPDATE tenders 
            SET submission_status = 'Approved', 
                outcome_status = 'Pending', 
                current_stage = 'WIN_LOSS_PENDING', 
                status = 'Submitted' 
            WHERE id = ?
          `).run(approvalReq.tender_id);

          const existingOutcome = db.prepare(
            "SELECT id FROM tender_approval_requests WHERE tender_id = ? AND stage = 'WIN_LOSS_PENDING' AND status = 'PENDING'"
          ).get(approvalReq.tender_id);

          if (!existingOutcome) {
            db.prepare(`
              INSERT INTO tender_approval_requests (tender_id, stage, requested_by, assigned_to, loss_reason_executive, status, created_at, updated_at)
              VALUES (?, 'WIN_LOSS_PENDING', ?, ?, ?, 'PENDING', ?, ?)
            `).run(
              approvalReq.tender_id,
              tender.mis_executive || auth.username || 'executive',
              tender.assigned_mis_member || tender.assigned_mis_member_submission || 'misteam',
              tender.loss_reason || null,
              now,
              now
            );
          }
        } else if (approvalReq.stage === 'WIN_LOSS_PENDING') {
          const isLoss = outcome === 'Lost' || (outcome !== 'Won' && (action === 'REJECTED' || !!lossReasonMis));
          const finalStatus = isLoss ? 'Not Awarded' : 'Awarded';
          const finalStage = isLoss ? 'LOST' : 'WON';
          const finalOutcome = isLoss ? 'Lost' : 'Won';
          const finalLossReason = isLoss ? (lossReasonMis || approvalReq.loss_reason_executive || 'Not Awarded') : null;

          db.prepare(`
            UPDATE tenders 
            SET outcome_status = ?, 
                status = ?, 
                current_stage = ?, 
                loss_reason = ? 
            WHERE id = ?
          `).run(finalOutcome, finalStatus, finalStage, finalLossReason, approvalReq.tender_id);

          db.prepare(`
            UPDATE tender_approval_requests 
            SET status = 'APPROVED', loss_reason_mis = ?, updated_at = ? 
            WHERE id = ?
          `).run(lossReasonMis || null, now, approvalReq.id);
        }
      }
    });

    updateTx();
    reconcileApprovalRequests();

    // ── Asynchronous Sync to Spring Boot Backend ─────────────────────────────
    const backendUrl = process.env.BACKEND_URL || 'http://localhost:8090';
    if (backendUrl && backendUrl !== 'http://localhost:8080') {
      try {
        const authH = request.headers.get('authorization');
        const fHeaders: Record<string, string> = {
          'Content-Type': 'application/json',
          Accept: 'application/json'
        };
        if (authH) fHeaders['authorization'] = authH;

        // 1. Sync tender status to backend via PATCH
        const patchBody: Record<string, any> = {};
        if (approvalReq.stage === 'DOC_VERIFICATION') {
          patchBody.verification_status = action === 'APPROVED' ? 'Approved' : 'Rejected';
          if (action === 'APPROVED') patchBody.current_stage = 'PAYMENT_APPROVAL';
        } else if (approvalReq.stage === 'PAYMENT_APPROVAL') {
          patchBody.payment_status = action === 'APPROVED' ? 'Approved' : 'Rejected';
          if (action === 'APPROVED') patchBody.current_stage = 'SUBMISSION_PENDING';
        } else if (approvalReq.stage === 'SPEC_CLEARANCE') {
          patchBody.spec_verification_status = action === 'APPROVED' ? 'Approved' : 'Rejected';
          if (action === 'APPROVED') patchBody.current_stage = 'TPC_PRICING';
        } else if (approvalReq.stage === 'SUBMISSION_PENDING') {
          patchBody.submission_status = action === 'APPROVED' ? 'Approved' : 'Rejected';
          if (action === 'APPROVED') {
            patchBody.current_stage = 'WIN_LOSS_PENDING';
            patchBody.status = 'Submitted';
          }
        } else if (approvalReq.stage === 'WIN_LOSS_PENDING') {
          patchBody.outcome_status = outcome === 'Lost' ? 'Lost' : 'Won';
          patchBody.status = outcome === 'Lost' ? 'Not Awarded' : 'Awarded';
          patchBody.current_stage = outcome === 'Lost' ? 'LOST' : 'WON';
        }

        if (Object.keys(patchBody).length > 0) {
          fetch(`${backendUrl}/api/tenders/${approvalReq.tender_id}`, {
            method: 'PATCH',
            headers: fHeaders,
            body: JSON.stringify(patchBody),
            cache: 'no-store'
          }).catch(() => {});
        }

        // 2. Forward review to backend approval review endpoint
        fetch(`${backendUrl}/api/approvals/${approvalReq.id}/review`, {
          method: 'POST',
          headers: fHeaders,
          body: JSON.stringify({
            ...body,
            tenderId: approvalReq.tender_id,
            stage: approvalReq.stage
          }),
          cache: 'no-store'
        }).catch(() => {});
      } catch (e) {}
    }

    return NextResponse.json({
      success: true,
      message: `Approval request #${approvalReq.id} for ${approvalReq.stage} successfully ${action.toLowerCase()}`
    });

  } catch (error) {
    console.error('[api/approvals/[id]/review] Error:', error);
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
