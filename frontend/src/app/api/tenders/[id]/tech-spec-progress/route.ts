import {
  fetchSpecificationBackend, mapSpecificationDownloads, readSpecificationResponse,
  requirePathSegment, specificationFailure,
} from '@/lib/technicalSpecificationBackend';

export const runtime = 'nodejs';

export async function GET(request: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    const { id } = await params;
    requirePathSegment(id);
    const response = await fetchSpecificationBackend(request, `/api/tenders/${encodeURIComponent(id)}/tech-spec-progress`);
    const result = await readSpecificationResponse(response);
    return Response.json(response.ok ? mapSpecificationDownloads(result, request, id) : result,
      { status: response.status, headers: { 'Cache-Control': 'no-store' } });
  } catch (error) {
    return specificationFailure(error, request);
  }
}
