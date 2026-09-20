import fs from 'node:fs/promises';
import path from 'node:path';
import { workflowActor, workflowForbidden } from '@/lib/workflowAuthorization';
import { requirePathSegment, SpecificationProxyError } from '@/lib/technicalSpecificationBackend';

export const runtime = 'nodejs';

export async function GET(request: Request, { params }: { params: Promise<{ id: string; filename: string }> }) {
  if (!workflowActor(request, 'viewTenders')) return workflowForbidden();
  try {
    const { id, filename } = await params;
    requirePathSegment(id);
    requirePathSegment(filename);
    if (!/\.(pdf|docx?|txt|zip|xlsx?|png|jpe?g|odt)$/i.test(filename)) {
      throw new SpecificationProxyError(400, 'Unsupported document type.');
    }
    const root = await fs.realpath(path.resolve(process.cwd(), 'public', 'documents'));
    const directory = path.resolve(root, id);
    if (!directory.startsWith(root + path.sep)) throw new SpecificationProxyError(400, 'Invalid document path.');
    const file = await fs.realpath(path.resolve(directory, filename));
    if (!file.startsWith(directory + path.sep) || !(await fs.stat(file)).isFile()) {
      throw new SpecificationProxyError(400, 'Invalid document path.');
    }
    const bytes = await fs.readFile(file, { signal: request.signal });
    const extension = path.extname(filename).toLowerCase();
    const types: Record<string, string> = {
      '.pdf': 'application/pdf',
      '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      '.zip': 'application/zip',
    };
    return new Response(new Uint8Array(bytes), { headers: {
      'Content-Type': types[extension] || 'application/octet-stream',
      'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(filename)}`,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    } });
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    const status = error instanceof SpecificationProxyError ? error.status : code === 'ENOENT' ? 404 : 500;
    return Response.json({ success: false, error: status === 404 ? 'Document not found.'
      : error instanceof SpecificationProxyError ? error.message : 'Could not read the document.' }, { status });
  }
}
