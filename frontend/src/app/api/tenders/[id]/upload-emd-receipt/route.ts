import { NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';
import db, { addActivityLog } from '@/lib/db';
import { getAuthFromRequest, getTokenFromRequest } from '@/lib/auth';
import { localDocumentUrl } from '@/lib/technicalSpecificationBackend';

export const runtime = 'nodejs';

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const auth = getAuthFromRequest(request);
    if (!auth) {
      return NextResponse.json(
        { success: false, error: 'Unauthorized: Authentication required.' },
        { status: 401 }
      );
    }

    const { id } = await params;
    if (!id || id.includes('..') || id.includes('/') || id.includes('\\')) {
      return NextResponse.json({ success: false, error: 'Invalid tender ID.' }, { status: 400 });
    }

    const formData = await request.formData();
    const file = formData.get('file') as File | null;

    if (!file || file.size === 0) {
      return NextResponse.json({ success: false, error: 'No file uploaded.' }, { status: 400 });
    }

    // Verify role permissions: Executive only (Admin is view-only)
    const role = auth.role;
    const isExec = role === 'Tender Executive';
    if (!isExec) {
      return NextResponse.json(
        { success: false, error: 'Only the assigned Tender Executive can upload EMD payment proof.' },
        { status: 403 }
      );
    }

    const rawName = file.name || 'receipt.pdf';
    const cleanName = rawName.replace(/[^a-zA-Z0-9._-]/g, '_');
    const fileName = `EMD_Receipt_${id}_${cleanName}`;
    const arrayBuffer = await file.arrayBuffer();
    const buffer = Buffer.from(arrayBuffer);

    // Save to all candidate locations
    const candidateDirs = [
      path.resolve(process.cwd(), 'public', 'documents', id),
      path.resolve(process.cwd(), '..', 'backend', 'public', 'documents', id),
      path.resolve(process.cwd(), 'backend', 'public', 'documents', id),
      path.resolve(process.cwd(), '..', 'public', 'documents', id),
      path.resolve(process.cwd(), '..', 'frontend', 'public', 'documents', id)
    ];

    for (const dir of candidateDirs) {
      try {
        if (!fs.existsSync(dir)) {
          fs.mkdirSync(dir, { recursive: true });
        }
        fs.writeFileSync(path.resolve(dir, fileName), buffer);
      } catch (_) {}
    }

    const docPath = localDocumentUrl(id, fileName);

    // Update SQLite DB
    try {
      db.prepare(`
        UPDATE tenders 
        SET emd_receipt_doc_path = ?, 
            emd_receipt_confidential = 1 
        WHERE id = ?
      `).run(docPath, id);
    } catch (dbErr) {
      console.warn('[upload-emd-receipt] SQLite update error:', dbErr);
    }

    // Forward to Spring Boot backend
    const backendUrl = process.env.BACKEND_URL || 'http://localhost:8090';
    try {
      const backendForm = new FormData();
      const blob = new Blob([buffer], { type: file.type || 'application/pdf' });
      backendForm.append('file', blob, fileName);

      const headers: Record<string, string> = {
        'x-user-role': auth.role,
        'x-user-username': auth.username,
      };
      const token = getTokenFromRequest(request);
      if (token) headers['authorization'] = `Bearer ${token}`;

      await fetch(`${backendUrl}/api/tenders/${encodeURIComponent(id)}/upload-emd-receipt`, {
        method: 'POST',
        headers,
        body: backendForm
      });
    } catch (backendErr) {
      console.warn('[upload-emd-receipt] Backend sync warning:', backendErr);
    }

    // Audit log
    try {
      addActivityLog(
        auth.username,
        auth.role,
        'Uploaded Confidential EMD Receipt',
        id,
        `Attached confidential EMD payment proof: ${fileName}`
      );
    } catch (_) {}

    return NextResponse.json({
      success: true,
      message: 'Confidential EMD payment receipt uploaded successfully.',
      emd_receipt_doc_path: docPath,
      emd_receipt_confidential: true
    });
  } catch (error: any) {
    console.error('[upload-emd-receipt] Error:', error);
    return NextResponse.json(
      { success: false, error: error.message || 'Failed to upload EMD receipt.' },
      { status: 500 }
    );
  }
}
