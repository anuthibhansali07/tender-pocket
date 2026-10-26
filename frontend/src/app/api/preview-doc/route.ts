import { NextResponse } from 'next/server';
import { generateHtmlTemplates, generateTechnicalSpecificationHtml } from '@/lib/documentTemplates';

export async function POST(request: Request) {
  try {
    const body = await request.json();
    const { companyKey = 'me', docType = 'all', orientation = 'portrait', sampleData = {} } = body;
    const effectiveOrientation = docType === 'spec' ? 'landscape' : (sampleData.orientation || orientation);

    const data = {
      companyKey,
      ...sampleData,
      orientation: effectiveOrientation
    };

    let html = '';
    if (docType === 'spec') {
      html = generateTechnicalSpecificationHtml(data, []);
    } else {
      html = generateHtmlTemplates(data);
    }

    return NextResponse.json({
      success: true,
      html
    });
  } catch (error: any) {
    console.error('[API preview-doc error]:', error);
    return NextResponse.json(
      { success: false, error: error.message || 'Failed to render document preview' },
      { status: 500 }
    );
  }
}
