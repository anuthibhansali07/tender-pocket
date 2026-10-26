import { proxyToBackend } from '@/lib/backendProxy';

export async function GET(
  request: Request,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  const backendPath = `/api/v2/workflow/${path.join('/')}`;
  return proxyToBackend(request, backendPath);
}

export async function POST(
  request: Request,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  const backendPath = `/api/v2/workflow/${path.join('/')}`;
  return proxyToBackend(request, backendPath);
}

export async function PUT(
  request: Request,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  const backendPath = `/api/v2/workflow/${path.join('/')}`;
  return proxyToBackend(request, backendPath);
}
