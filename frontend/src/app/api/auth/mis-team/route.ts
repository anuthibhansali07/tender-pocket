import { NextResponse } from 'next/server';
import db from '@/lib/db';
import { workflowActor, workflowForbidden } from '@/lib/workflowAuthorization';

export async function GET(request: Request) {
  try {
    if (!workflowActor(request, 'viewTenders')) return workflowForbidden();
    const stmt = db.prepare("SELECT username FROM users WHERE role = 'MIS Team' ORDER BY username ASC");
    const misTeam = (stmt.all() as { username: string }[]).map(r => r.username);

    return NextResponse.json({
      success: true,
      misTeam
    });
  } catch (error) {
    console.error('Error fetching MIS Team users:', error);
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
