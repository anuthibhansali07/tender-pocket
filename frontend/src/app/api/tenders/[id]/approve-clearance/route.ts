import { NextResponse } from 'next/server';
import db from '@/lib/db';
import { workflowActor, workflowForbidden } from '@/lib/workflowAuthorization';

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const auth = workflowActor(request, 'approveSpecs');
    if (!auth) return workflowForbidden();
    const { id } = await params;
    const tender = db.prepare('SELECT spec_verification_status FROM tenders WHERE id = ?').get(id) as
      { spec_verification_status: string } | undefined;
    if (!tender) return NextResponse.json({ success: false, error: 'Tender not found' }, { status: 404 });
    if (tender.spec_verification_status !== 'Pending') {
      return NextResponse.json({ success: false, error: 'Specification clearance is not pending.' }, { status: 409 });
    }
    const body = await request.json().catch(() => ({}));
    const backendUrl = process.env.BACKEND_URL || 'http://localhost:8090';

    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 2000);
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      };
      const auth = request.headers.get('authorization');
      if (auth) headers['authorization'] = auth;

      await fetch(`${backendUrl}/api/tenders/${id}/approve-clearance`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: controller.signal,
        cache: 'no-store'
      });
      clearTimeout(timeout);
    } catch (e) {}

    // Always ensure local SQLite database is updated
    const now = new Date().toISOString();
    db.prepare("UPDATE tenders SET spec_verification_status = 'Approved', current_stage = 'TPC_PRICING' WHERE id = ?").run(id);

    try {
      db.prepare("UPDATE tender_approval_requests SET status = 'APPROVED', updated_at = ? WHERE tender_id = ? AND stage = 'SPEC_CLEARANCE' AND status = 'PENDING'").run(now, id);
      
      const existingTpc = db.prepare("SELECT id FROM tender_approval_requests WHERE tender_id = ? AND stage = 'TPC_PRICING' AND status = 'PENDING'").get(id);
      if (!existingTpc) {
        db.prepare(`
          INSERT INTO tender_approval_requests (tender_id, stage, requested_by, assigned_to, status, created_at, updated_at)
          VALUES (?, 'TPC_PRICING', 'clearance', 'tpc', 'PENDING', ?, ?)
        `).run(id, now, now);
      }
    } catch (e) {}

    return NextResponse.json({
      success: true,
      message: 'Technical specification approved! Tender forwarded to TPC Team for purchase pricing.'
    });
  } catch (error) {
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
