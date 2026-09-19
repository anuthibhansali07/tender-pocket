import { NextResponse } from 'next/server';
import db from '@/lib/db';
import { workflowActor, workflowForbidden } from '@/lib/workflowAuthorization';

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    if (!workflowActor(request, 'setMisPrice')) return workflowForbidden();
    const { id } = await params;
    const body = await request.json();
    const rawPrice = body?.misFinalPrice;
    const price = typeof rawPrice === 'number' || typeof rawPrice === 'string' ? Number(rawPrice) : NaN;

    if (!Number.isFinite(price) || price <= 0) {
      return NextResponse.json(
        { success: false, error: 'Valid MIS final price is required' },
        { status: 400 }
      );
    }
    const tender = db.prepare('SELECT spec_verification_status, tpc_purchase_price FROM tenders WHERE id = ?').get(id) as
      { spec_verification_status: string; tpc_purchase_price: number | null } | undefined;
    if (!tender) return NextResponse.json({ success: false, error: 'Tender not found' }, { status: 404 });
    if (tender.spec_verification_status !== 'Approved' || !Number.isFinite(tender.tpc_purchase_price)
        || Number(tender.tpc_purchase_price) <= 0) {
      return NextResponse.json({ success: false, error: 'Approved specifications and a TPC quote are required before MIS pricing.' }, { status: 409 });
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

      await fetch(`${backendUrl}/api/tenders/${id}/mis-price`, {
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
      db.prepare('UPDATE tenders SET mis_final_price = ?, current_stage = ? WHERE id = ?').run(price, 'BID_DOC_PENDING', id);
    } catch (err) {
      try {
        db.exec('ALTER TABLE tenders ADD COLUMN mis_final_price REAL');
      } catch (e) { /* already exists */ }
      try {
        db.exec('ALTER TABLE tenders ADD COLUMN current_stage TEXT');
      } catch (e) { /* already exists */ }
      db.prepare('UPDATE tenders SET mis_final_price = ?, current_stage = ? WHERE id = ?').run(price, 'BID_DOC_PENDING', id);
    }

    try {
      const now = new Date().toISOString();
      db.prepare("UPDATE tender_approval_requests SET status = 'APPROVED', mis_final_price = ?, updated_at = ? WHERE tender_id = ? AND stage = 'MIS_PRICING' AND status = 'PENDING'").run(price, now, id);
    } catch (e) {}

    return NextResponse.json({
      success: true,
      message: `Final purchase price (₹${price.toLocaleString('en-IN')}) configured for Tender Executive.`,
      misFinalPrice: price
    });
  } catch (error) {
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
