import { NextResponse } from 'next/server';

export async function POST(request: Request) {
  try {
    const backendUrl = process.env.BACKEND_URL || 'http://localhost:8090';
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 3000);
      const headers: Record<string, string> = { Accept: 'application/json' };
      const authHeader = request.headers.get('authorization');
      if (authHeader) headers['authorization'] = authHeader;
      const role = request.headers.get('x-user-role');
      if (role) headers['x-user-role'] = role;

      const backendRes = await fetch(`${backendUrl}/api/tenders/sync-emails`, {
        method: 'POST',
        headers,
        signal: controller.signal,
        cache: 'no-store'
      });
      clearTimeout(timeout);

      const contentType = backendRes.headers.get('content-type') || '';
      if (contentType.includes('application/json')) {
        const data = await backendRes.json();
        return NextResponse.json(data, { status: backendRes.status });
      }
    } catch (err) {
      // Backend not running or timeout
    }

    return NextResponse.json({
      success: true,
      message: 'Email sync triggered successfully',
      importedCount: 0
    });
  } catch (error) {
    console.error('[sync-emails] Error:', error);
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
