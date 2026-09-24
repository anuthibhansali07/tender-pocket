import fs from 'node:fs';
import path from 'node:path';
import axios from 'axios';
import { downloadAndSaveTenderDocuments } from '@/lib/scraper';

export const runtime = 'nodejs';

export async function GET(request: Request, { params }: { params: Promise<{ id: string; filename: string | string[] }> }) {
  try {
    const rawParams = await params;
    const safeId = path.basename(rawParams.id);
    
    // Support string or array of path parts
    const filenameSegments = Array.isArray(rawParams.filename) 
      ? rawParams.filename.map(p => path.basename(p))
      : [path.basename(rawParams.filename)];
    
    const filenameStr = filenameSegments.join('/');

    const docsDir = path.resolve(process.cwd(), 'public', 'documents');
    let targetPath = path.resolve(docsDir, safeId, ...filenameSegments);

    if (!targetPath.startsWith(docsDir + path.sep)) {
      return Response.json({ success: false, error: 'Invalid document path.' }, { status: 400 });
    }

    // If file does not exist on local disk, attempt on-demand download/recovery
    if (!fs.existsSync(targetPath) || !fs.statSync(targetPath).isFile()) {
      console.log(`[Document Download API] ${filenameStr} missing for Tender ${safeId}. Attempting on-demand retrieval...`);
      
      const targetDir = path.dirname(targetPath);
      if (!fs.existsSync(targetDir)) {
        fs.mkdirSync(targetDir, { recursive: true });
      }

      const lowerName = filenameStr.toLowerCase();

      // 1. If it's a GeM Bid PDF (e.g., Bid_Document_9822037.pdf or similar)
      if (lowerName.startsWith('bid_document_') && lowerName.endsWith('.pdf')) {
        try {
          const gemUrl = `https://bidplus.gem.gov.in/showbidDocument/${safeId}`;
          console.log(`[Document Download API] Fetching directly from GeM: ${gemUrl}`);
          const res = await axios.get(gemUrl, {
            responseType: 'arraybuffer',
            headers: {
              'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            },
            timeout: 15000
          });
          if (res.status === 200 && res.data && res.data.length > 500) {
            fs.writeFileSync(targetPath, res.data);
            console.log(`[Document Download API] Saved ${filenameStr} (${res.data.length} bytes) to ${targetPath}`);
          }
        } catch (gemErr: any) {
          console.warn(`[Document Download API] Direct GeM download failed: ${gemErr.message}`);
        }
      }

      // 2. If it's a GeM RA PDF (e.g., GeM-RA-9822037.pdf)
      if (!fs.existsSync(targetPath) && lowerName.startsWith('gem-ra-') && lowerName.endsWith('.pdf')) {
        try {
          const raUrl = `https://bidplus.gem.gov.in/showradocument/${safeId}`;
          console.log(`[Document Download API] Fetching RA document from GeM: ${raUrl}`);
          const res = await axios.get(raUrl, {
            responseType: 'arraybuffer',
            headers: {
              'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            },
            timeout: 15000
          });
          if (res.status === 200 && res.data && res.data.length > 500) {
            fs.writeFileSync(targetPath, res.data);
            console.log(`[Document Download API] Saved RA document ${filenameStr} to ${targetPath}`);
          }
        } catch (raErr: any) {
          console.warn(`[Document Download API] GeM RA download failed: ${raErr.message}`);
        }
      }

      // 3. Fallback: try downloadAndSaveTenderDocuments via Tender247 scraper
      if (!fs.existsSync(targetPath)) {
        try {
          console.log(`[Document Download API] Invoking downloadAndSaveTenderDocuments for tender ${safeId}...`);
          await downloadAndSaveTenderDocuments(safeId);
        } catch (scraperErr: any) {
          console.warn(`[Document Download API] Scraper download failed: ${scraperErr.message}`);
        }
      }

      // 4. Case-insensitive or fuzzy match within safeId directory
      if (!fs.existsSync(targetPath) || !fs.statSync(targetPath).isFile()) {
        const tenderDocDir = path.resolve(docsDir, safeId);
        if (fs.existsSync(tenderDocDir)) {
          const files = fs.readdirSync(tenderDocDir);
          const baseNameOnly = path.basename(filenameStr).toLowerCase();
          const matched = files.find(f => f.toLowerCase() === baseNameOnly);
          if (matched) {
            targetPath = path.resolve(tenderDocDir, matched);
          }
        }
      }
    }

    if (!fs.existsSync(targetPath) || !fs.statSync(targetPath).isFile()) {
      return Response.json({ success: false, error: 'Document file is not available on site.' }, { status: 404 });
    }

    const bytes = fs.readFileSync(targetPath);
    const extension = path.extname(targetPath).toLowerCase();
    const types: Record<string, string> = {
      '.pdf': 'application/pdf',
      '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      '.zip': 'application/zip',
      '.csv': 'text/csv',
      '.png': 'image/png',
      '.jpg': 'image/jpeg',
      '.jpeg': 'image/jpeg'
    };

    const finalFileName = path.basename(targetPath);

    return new Response(new Uint8Array(bytes), {
      headers: {
        'Content-Type': types[extension] || 'application/octet-stream',
        'Content-Disposition': `attachment; filename="${finalFileName}"`,
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
      }
    });
  } catch (error) {
    console.error('[Document Download API Error]:', error);
    return Response.json({ success: false, error: 'Could not read the document.' }, { status: 500 });
  }
}
