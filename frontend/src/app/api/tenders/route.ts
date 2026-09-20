import { NextResponse } from 'next/server';
import db, { Tender } from '@/lib/db';

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';

export async function GET(request: Request) {
  try {
    const { searchParams } = new URL(request.url);

    // Build backend query parameters
    const targetParams = new URLSearchParams();
    const forwardedParamKeys = [
      'page',
      'size',
      'search',
      'status',
      'location',
      'sector',
      'source',
      'min_cost',
      'minCost',
      'max_cost',
      'maxCost',
      'mis_executive',
      'sortBy',
      'sort_by',
      'order',
      'sort_dir'
    ];

    for (const key of forwardedParamKeys) {
      const val = searchParams.get(key);
      if (val !== null && val !== undefined && val !== '') {
        targetParams.set(key, val);
      }
    }

    // Default pagination if not provided
    if (!targetParams.has('page')) {
      targetParams.set('page', '0');
    }
    if (!targetParams.has('size')) {
      targetParams.set('size', '20');
    }

    // Forward auth & user headers without logging credentials
    const forwardHeaders: Record<string, string> = {
      'Content-Type': 'application/json'
    };
    const userRole = request.headers.get('x-user-role');
    const username = request.headers.get('x-user-username');
    const authHeader = request.headers.get('authorization');

    if (userRole) forwardHeaders['x-user-role'] = userRole;
    if (username) forwardHeaders['x-user-username'] = username;
    if (authHeader) forwardHeaders['authorization'] = authHeader;

    try {
      const backendRes = await fetch(`${BACKEND_URL}/api/tenders?${targetParams.toString()}`, {
        method: 'GET',
        headers: forwardHeaders,
        cache: 'no-store'
      });

      const data = await backendRes.json();
      return NextResponse.json(data, { status: backendRes.status });
    } catch (fetchErr) {
      console.error('Failed to proxy request to backend Spring Boot:', fetchErr instanceof Error ? fetchErr.message : String(fetchErr));
      return NextResponse.json(
        {
          success: false,
          error: 'Backend service unreachable',
          tenders: [],
          totalElements: 0,
          totalPages: 0,
          currentPage: 0,
          pageSize: 20
        },
        { status: 502 }
      );
    }
  } catch (error) {
    console.error('Error in /api/tenders route handler:', error);
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}

export async function POST(request: Request) {
  try {
    const body = await request.json();

    // First attempt to proxy to Spring Boot backend
    const forwardHeaders: Record<string, string> = {
      'Content-Type': 'application/json'
    };
    const userRole = request.headers.get('x-user-role');
    const username = request.headers.get('x-user-username');
    const authHeader = request.headers.get('authorization');

    if (userRole) forwardHeaders['x-user-role'] = userRole;
    if (username) forwardHeaders['x-user-username'] = username;
    if (authHeader) forwardHeaders['authorization'] = authHeader;

    try {
      const backendRes = await fetch(`${BACKEND_URL}/api/tenders`, {
        method: 'POST',
        headers: forwardHeaders,
        body: JSON.stringify(body)
      });
      const data = await backendRes.json();
      return NextResponse.json(data, { status: backendRes.status });
    } catch (proxyErr) {
      // Fallback to SQLite local insertion if backend is unreachable
      console.warn('Backend unavailable for POST /api/tenders, falling back to local SQLite:', proxyErr);
      const {
        id, ref_no, title, authority, estimated_cost, estimated_cost_raw,
        emd, emd_raw, document_fee, document_fee_raw, location, sector,
        due_date, opening_date, document_url, original_url, status
      } = body;

      if (!id || !title || !original_url) {
        return NextResponse.json(
          { success: false, error: 'id, title, and original_url are required' },
          { status: 400 }
        );
      }

      const stmt = db.prepare(`
        INSERT OR REPLACE INTO tenders (
          id, ref_no, title, authority, estimated_cost, estimated_cost_raw,
          emd, emd_raw, document_fee, document_fee_raw, location, sector,
          due_date, opening_date, document_url, original_url, status, scraped_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `);

      stmt.run(
        id,
        ref_no || null,
        title,
        authority || null,
        estimated_cost || null,
        estimated_cost_raw || null,
        emd || null,
        emd_raw || null,
        document_fee || null,
        document_fee_raw || null,
        location || null,
        sector || null,
        due_date || null,
        opening_date || null,
        document_url || null,
        original_url,
        status || 'new',
        new Date().toISOString()
      );

      return NextResponse.json({ success: true, message: 'Tender added successfully (local fallback)' });
    }
  } catch (error) {
    console.error('Error creating tender:', error);
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
