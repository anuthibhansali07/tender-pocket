import { NextResponse } from 'next/server';
import db from '@/lib/db';
import { getAuthFromRequest } from '@/lib/auth';

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const backendUrl = process.env.BACKEND_URL || 'http://localhost:8090';

    let body: any = {};
    try {
      body = await request.json();
    } catch (e) {}

    const auth = getAuthFromRequest(request);
    const requestedBy = auth?.username || request.headers.get('x-user-username') || 'executive';
    const authHeader = request.headers.get('authorization');

    const assignedTo = body.assignedClearanceRep || body.assignedTo || 'clearance';
    const now = new Date().toISOString();

    // 1. Try Spring Boot backend
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 2000);
      const headers: Record<string, string> = { 
        'Content-Type': 'application/json',
        Accept: 'application/json' 
      };
      if (authHeader) headers['authorization'] = authHeader;

      await fetch(`${backendUrl}/api/tenders/${id}/clearance-request`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: controller.signal,
        cache: 'no-store'
      });
      clearTimeout(timeout);
    } catch (e) {}

    // 2. Always ensure local SQLite database is updated
    db.prepare("UPDATE tenders SET spec_verification_status = 'Pending', current_stage = 'SPEC_CLEARANCE', assigned_mis_member_spec = ? WHERE id = ?").run(assignedTo, id);

    // Record in tender_approval_requests (PATCH existing rather than creating redundant request)
    try {
      const existing = db.prepare(`
        SELECT id FROM tender_approval_requests 
        WHERE tender_id = ? AND stage = 'SPEC_CLEARANCE' AND status = 'PENDING'
        ORDER BY id DESC
      `).all(id) as any[];

      if (existing.length > 0) {
        // Patch the latest existing pending request
        db.prepare(`
          UPDATE tender_approval_requests 
          SET assigned_to = ?, requested_by = ?, updated_at = ?
          WHERE id = ?
        `).run(assignedTo, requestedBy, now, existing[0].id);

        // Remove any redundant duplicates
        for (let i = 1; i < existing.length; i++) {
          db.prepare("DELETE FROM tender_approval_requests WHERE id = ?").run(existing[i].id);
        }
      } else {
        db.prepare(`
          INSERT INTO tender_approval_requests (tender_id, stage, requested_by, assigned_to, status, created_at, updated_at)
          VALUES (?, 'SPEC_CLEARANCE', ?, ?, 'PENDING', ?, ?)
        `).run(id, requestedBy, assignedTo, now, now);
      }
    } catch (e) {}

    return NextResponse.json({
      success: true,
      message: 'Submitted to Clearance Team for Approval!'
    });
  } catch (error) {
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
