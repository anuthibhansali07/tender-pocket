import { NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';
import db, { addActivityLog } from '@/lib/db';
import { getAuthFromRequest } from '@/lib/auth';

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    if (!id) {
      return NextResponse.json({ success: false, error: 'Tender ID is required' }, { status: 400 });
    }

    const auth = getAuthFromRequest(request);
    const username = auth?.username || request.headers.get('x-user-username') || 'system';
    const userRole = auth?.role || request.headers.get('x-user-role') || 'User';

    const formData = await request.formData();
    const file = formData.get('file') as File | null;

    if (!file || typeof file === 'string') {
      return NextResponse.json({ success: false, error: 'No file uploaded' }, { status: 400 });
    }

    const buffer = Buffer.from(await file.arrayBuffer());
    if (buffer.length === 0) {
      return NextResponse.json({ success: false, error: 'Uploaded file is empty' }, { status: 400 });
    }

    // Ensure document directory exists: public/documents/{id}
    const docDir = path.join(process.cwd(), 'public', 'documents', id);
    if (!fs.existsSync(docDir)) {
      fs.mkdirSync(docDir, { recursive: true });
    }

    const originalFilename = file.name || 'specification.pdf';
    const targetFilePath = path.join(docDir, originalFilename);
    const specFilePath = path.join(docDir, 'specification.pdf');

    // Save uploaded file
    fs.writeFileSync(targetFilePath, buffer);
    // Also save copy as specification.pdf for standard spec parser lookup
    if (originalFilename !== 'specification.pdf') {
      fs.writeFileSync(specFilePath, buffer);
    }

    // Attempt forwarding to Spring Boot backend if available
    const backendUrl = process.env.BACKEND_URL;
    if (backendUrl && backendUrl !== 'http://localhost:8080') {
      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 3000);
        const backendFormData = new FormData();
        const blob = new Blob([buffer], { type: file.type || 'application/pdf' });
        backendFormData.append('file', blob, originalFilename);

        const headers: Record<string, string> = {
          'x-user-role': userRole,
          'x-user-username': username
        };
        const authH = request.headers.get('authorization');
        if (authH) headers['authorization'] = authH;

        await fetch(`${backendUrl}/api/tenders/${id}/upload-tech-spec`, {
          method: 'POST',
          headers,
          body: backendFormData,
          signal: controller.signal
        });
        clearTimeout(timeout);
      } catch (backendErr) {
        // Non-critical: Spring Boot sync failure is safely bypassed for local resilience
      }
    }

    // Update SQLite database: register document and update status
    try {
      const tender = db.prepare('SELECT downloaded_docs FROM tenders WHERE id = ?').get(id) as any;
      let currentDocs = [];
      try {
        currentDocs = JSON.parse(tender?.downloaded_docs || '[]');
        if (!Array.isArray(currentDocs)) currentDocs = [];
      } catch (_) {
        currentDocs = [];
      }

      const docUrl = `/documents/${id}/${originalFilename}`;
      const docEntry = {
        name: `Uploaded Technical Specification (${originalFilename})`,
        filename: originalFilename,
        local_path: docUrl,
        created_date: new Date().toLocaleDateString('en-IN')
      };

      currentDocs = currentDocs.filter((d: any) => d.local_path !== docUrl && d.filename !== originalFilename);
      currentDocs.push(docEntry);

      db.prepare(`
        UPDATE tenders 
        SET spec_verification_status = 'Generated',
            downloaded_docs = ?
        WHERE id = ?
      `).run(JSON.stringify(currentDocs), id);

      addActivityLog(username, userRole, 'UPLOAD_TECH_SPEC', id, `Uploaded technical specification: ${originalFilename}`);
    } catch (dbErr) {
      console.error('[upload-tech-spec] SQLite update error:', dbErr);
    }

    return NextResponse.json({
      success: true,
      message: 'Technical specification uploaded successfully',
      filename: originalFilename,
      url: `/documents/${id}/${originalFilename}`
    });

  } catch (error) {
    console.error('[upload-tech-spec] Route error:', error);
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : 'Internal server error during upload' },
      { status: 500 }
    );
  }
}
