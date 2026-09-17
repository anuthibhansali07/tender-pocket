import { NextResponse } from 'next/server';
import db from '@/lib/db';

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const body = await request.json();
    const price = parseFloat(body.misFinalPrice);

    if (isNaN(price) || price <= 0) {
      return NextResponse.json(
        { success: false, error: 'Valid MIS final price is required' },
        { status: 400 }
      );
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
