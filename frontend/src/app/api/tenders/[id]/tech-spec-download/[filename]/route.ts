import {
  fetchSpecificationBackend, readSpecificationResponse, requirePathSegment, specificationFailure,
  SpecificationProxyError,
} from '@/lib/technicalSpecificationBackend';

export const runtime = 'nodejs';

export async function GET(request: Request, { params }: { params: Promise<{ id: string; filename: string }> }) {
  try {
    const { id, filename } = await params;
    requirePathSegment(id);
    requirePathSegment(filename);
    if (!/\.(pdf|docx)$/i.test(filename)) throw new SpecificationProxyError(400, 'Only PDF/DOCX sheet downloads are supported.');
    const response = await fetchSpecificationBackend(request,
      `/documents/${encodeURIComponent(id)}/${encodeURIComponent(filename)}`, { headers: { Accept: '*/*' } });
    if (!response.ok) return Response.json(await readSpecificationResponse(response),
      { status: response.status, headers: { 'Cache-Control': 'no-store' } });
    return new Response(response.body, { status: response.status, headers: {
      'Content-Type': response.headers.get('content-type') || 'application/octet-stream',
      'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(filename)}`,
      'Cache-Control': 'no-store',
    } });
  } catch (error) {
    return specificationFailure(error, request);
  }
}
