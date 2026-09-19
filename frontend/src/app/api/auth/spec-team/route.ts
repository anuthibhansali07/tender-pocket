import { NextResponse } from 'next/server';
import db from '@/lib/db';
import { workflowActor, workflowForbidden } from '@/lib/workflowAuthorization';

export async function GET(request: Request) {
  try {
    if (!workflowActor(request, 'viewTenders')) return workflowForbidden();
    const stmt = db.prepare("SELECT username FROM users WHERE role IN ('Clearance Team', 'Specification Team') ORDER BY username ASC");
    const specTeam = (stmt.all() as { username: string }[]).map(r => r.username);

    return NextResponse.json({
      success: true,
      specTeam
    });
  } catch (error) {
    console.error('Error fetching Specification Team users:', error);
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
