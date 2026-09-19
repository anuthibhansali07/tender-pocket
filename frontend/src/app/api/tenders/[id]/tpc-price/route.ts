import { NextResponse } from 'next/server';
import db from '@/lib/db';
import { workflowActor, workflowForbidden } from '@/lib/workflowAuthorization';

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    if (!workflowActor(request, 'setTpcPrice')) return workflowForbidden();
    const { id } = await params;
    const body = await request.json();
    const rawPrice = body?.tpcPurchasePrice;
    const price = typeof rawPrice === 'number' || typeof rawPrice === 'string' ? Number(rawPrice) : NaN;

    if (!Number.isFinite(price) || price <= 0) {
      return NextResponse.json(
        { success: false, error: 'Valid manufacturer purchase price is required' },
        { status: 400 }
      );
    }
    const tender = db.prepare('SELECT spec_verification_status FROM tenders WHERE id = ?').get(id) as
      { spec_verification_status: string } | undefined;
    if (!tender) return NextResponse.json({ success: false, error: 'Tender not found' }, { status: 404 });
    if (tender.spec_verification_status !== 'Approved') {
      return NextResponse.json({ success: false, error: 'Specification clearance is required before TPC pricing.' }, { status: 409 });
    }

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

      await fetch(`${backendUrl}/api/tenders/${id}/tpc-price`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: controller.signal,
        cache: 'no-store'
      });
      clearTimeout(timeout);
    } catch (e) {}

    // Always ensure local SQLite database is updated
    try {
      db.prepare('UPDATE tenders SET tpc_purchase_price = ?, current_stage = ? WHERE id = ?').run(price, 'MIS_PRICING', id);
    } catch (err) {
      // If column doesn't exist yet, alter table then retry
      try {
        db.exec('ALTER TABLE tenders ADD COLUMN tpc_purchase_price REAL');
      } catch (e) { /* already exists */ }
      try {
        db.exec('ALTER TABLE tenders ADD COLUMN current_stage TEXT');
      } catch (e) { /* already exists */ }
      db.prepare('UPDATE tenders SET tpc_purchase_price = ?, current_stage = ? WHERE id = ?').run(price, 'MIS_PRICING', id);
    }

    try {
      const now = new Date().toISOString();
      db.prepare("UPDATE tender_approval_requests SET status = 'APPROVED', tpc_purchase_price = ?, updated_at = ? WHERE tender_id = ? AND stage = 'TPC_PRICING' AND status = 'PENDING'").run(price, now, id);
      
      const existingMis = db.prepare("SELECT id FROM tender_approval_requests WHERE tender_id = ? AND stage = 'MIS_PRICING' AND status = 'PENDING'").get(id) as any;
      if (existingMis) {
        db.prepare("UPDATE tender_approval_requests SET tpc_purchase_price = ?, updated_at = ? WHERE id = ?").run(price, now, existingMis.id);
      } else {
        db.prepare(`
          INSERT INTO tender_approval_requests (tender_id, stage, requested_by, assigned_to, tpc_purchase_price, status, created_at, updated_at)
          VALUES (?, 'MIS_PRICING', 'tpc', 'misteam', ?, 'PENDING', ?, ?)
        `).run(id, price, now, now);
      }
    } catch (e) {}

    return NextResponse.json({
      success: true,
      message: `Manufacturer purchase price (₹${price.toLocaleString('en-IN')}) submitted to MIS Team.`,
      tpcPurchasePrice: price
    });
  } catch (error) {
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
