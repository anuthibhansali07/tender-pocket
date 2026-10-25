import { NextResponse } from 'next/server';
import db, { type Tender, addActivityLog } from '@/lib/db';
import { workflowActor, workflowForbidden, canPerform, canonicalRole } from '@/lib/workflowAuthorization';
import { localDocumentUrl } from '@/lib/technicalSpecificationBackend';
import fs from 'fs';
import os from 'os';
import path from 'path';
import zlib from 'zlib';
import { execSync } from 'child_process';
import { generateHtmlTemplates, generateTechnicalSpecificationHtml, cleanHtmlForDocx, sanitizeDocxBuffer, getDocxHeaderHtml, getDocxFooterHtml } from '@/lib/documentTemplates';
import { formatDate } from '@/lib/documentLayout';

// @ts-ignore
import HTMLtoDOCX from 'html-to-docx';
import puppeteer from 'puppeteer';


function splitLine(line: string): { left: string, right: string } {
  const firstCharIdx = line.search(/\S/);
  if (firstCharIdx === -1) {
    return { left: '', right: '' };
  }
  
  if (firstCharIdx >= 30) {
    return { left: '', right: line.trim() };
  }
  
  const remaining = line.substring(firstCharIdx);
  const gapMatch = remaining.match(/\s{3,}/);
  if (gapMatch && gapMatch.index !== undefined) {
    const gapStart = firstCharIdx + gapMatch.index;
    const gapEnd = gapStart + gapMatch[0].length;
    
    if (gapEnd >= 30) {
      return {
        left: line.substring(0, gapStart).trim(),
        right: line.substring(gapEnd).trim()
      };
    }
  }
  
  return { left: line.trim(), right: '' };
}

let hasPdfToText: boolean | null = null;
function checkPdfToText(): boolean {
  if (hasPdfToText !== null) return hasPdfToText;
  try {
    execSync('pdftotext -v', { stdio: 'ignore' });
    hasPdfToText = true;
  } catch {
    hasPdfToText = false;
  }
  return hasPdfToText;
}

function extractRawTextFromPdf(pdfPath: string): string {
  if (checkPdfToText()) {
    try {
      const text = execSync(`pdftotext -layout "${pdfPath}" -`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
      if (text && text.trim().length >= 50) return text;
    } catch {}
  }
  try {
    const buf = fs.readFileSync(pdfPath);
    let text = '';
    let pos = 0;
    while ((pos = buf.indexOf('stream', pos)) !== -1) {
      pos += 6;
      if (buf[pos] === 0x0d && buf[pos + 1] === 0x0a) pos += 2;
      else if (buf[pos] === 0x0a) pos += 1;
      const end = buf.indexOf('endstream', pos);
      if (end === -1) break;
      const chunk = buf.slice(pos, end);
      let inflated = '';
      try {
        inflated = zlib.inflateSync(chunk).toString('utf-8');
      } catch {
        try {
          inflated = zlib.inflateRawSync(chunk).toString('utf-8');
        } catch {
          inflated = chunk.toString('latin1');
        }
      }
      const tjRegex = /\[([^\]]*)\]\s*TJ/g;
      let match;
      while ((match = tjRegex.exec(inflated)) !== null) {
        const parts = match[1].match(/\(([^)]*)\)/g);
        if (parts) text += parts.map(p => p.slice(1, -1)).join('') + ' ';
      }
      const singleTjRegex = /\(([^)]*)\)\s*Tj/g;
      while ((match = singleTjRegex.exec(inflated)) !== null) {
        text += match[1] + '\n';
      }
      pos = end + 9;
    }
    return text;
  } catch {
    return '';
  }
}

function extractSpecsFromPdf(id: string): { sr: number, parameter: string, value: string }[] {
  const candidateDirs = [
    path.join(process.cwd(), '..', 'backend', 'public', 'documents', id),
    path.join(process.cwd(), 'backend', 'public', 'documents', id),
    path.join(process.cwd(), 'public', 'documents', id),
    path.join(process.cwd(), '..', 'public', 'documents', id)
  ];

  let docDir = candidateDirs.find(d => fs.existsSync(d));
  if (!docDir) {
    console.log(`[extractSpecsFromPdf] Document directory not found for tender ${id}`);
    return [];
  }

  // Find all PDF files in the directory
  const files = fs.readdirSync(docDir).filter(f => f.toLowerCase().endsWith('.pdf'));
  
  const allSpecs: { parameter: string, value: string }[] = [];
  const seen = new Set<string>();

  for (const file of files) {
    // Skip generated files to avoid feedback loop
    if (file.startsWith('Bid_Documents_') || file.startsWith('Technical_Specification_Sheet_')) {
      continue;
    }
    
    const pdfPath = path.join(docDir, file);
    console.log(`[extractSpecsFromPdf] Processing PDF file: ${file} for tender ${id}`);

    try {
      const text = extractRawTextFromPdf(pdfPath);
      if (!text || text.trim().length < 50) continue;

      const isGemBid = text.includes('Technical Specifications') || text.includes('As per GeM') || text.includes('Allowed Values') || text.includes('Specification Name');
      const specs: { parameter: string, value: string }[] = [];

      if (isGemBid) {
        // Parse standard GeM Bid PDF layout using block-based parsing
        const startIdx = text.indexOf('Technical Specifications');
        const endIdx = text.indexOf('Consignees/Reporting Officer');
        const searchRangeText = (startIdx !== -1 && endIdx !== -1 && endIdx > startIdx)
          ? text.slice(startIdx, endIdx)
          : text;

        const lines = searchRangeText.split('\n');
        const blocks: { left: string[], right: string[] }[] = [];
        let currentBlock: { left: string[], right: string[] } | null = null;

        for (let line of lines) {
          const trimmed = line.trim();
          if (!trimmed) {
            if (currentBlock) {
              blocks.push(currentBlock);
              currentBlock = null;
            }
            continue;
          }

          if (/^\d+\s*\/\s*\d+$/.test(trimmed)) continue;
          if (trimmed.includes('Technical Specifications') || trimmed.includes('As per GeM') || trimmed.includes('Allowed Values') || trimmed.includes('Specification Parameter')) continue;
          if (trimmed.startsWith('ववरण/Specification') || trimmed.startsWith('विश का नाम') || trimmed.startsWith('बड के िलए')) continue;

          const { left, right } = splitLine(line);
          if (!currentBlock) {
            currentBlock = { left: [], right: [] };
          }
          if (left) currentBlock.left.push(left);
          if (right) currentBlock.right.push(right);
        }
        if (currentBlock) {
          blocks.push(currentBlock);
        }

        // Merge lines in each block and push to specs
        for (const block of blocks) {
          const p = block.left.filter(Boolean).join(' ').replace(/\s+/g, ' ').trim();
          const v = block.right.filter(Boolean).join(' ').replace(/\s+/g, ' ').trim();
          if (p && v) {
            specs.push({ parameter: p, value: v });
          }
        }
      } else {
        // Parse general/custom specification or BOQ PDF layout
        const lines = text.split('\n');
        let currentParam = '';
        let currentValue = '';

        for (let line of lines) {
          const trimmed = line.trim();
          if (!trimmed || /^\d+\s*\/\s*\d+$/.test(trimmed)) continue;
          if (trimmed.includes('SECTION') || trimmed.includes('KEYSPECIFICATIONS') || trimmed.includes('Item Title') || trimmed.includes('Item Description')) continue;

          const startCol = line.search(/\S/);
          const parts = line.split(/\s{3,}/).map(p => p.trim()).filter(Boolean);

          if (parts.length >= 3 && /^\d+$/.test(parts[0])) {
            if (currentParam) specs.push({ parameter: currentParam, value: currentValue });
            currentParam = parts[1];
            currentValue = parts[2];
          } else {
            if (parts.length >= 2) {
              const leftColText = parts[0];
              const rightColText = parts.slice(1).join(' ');
              if (currentParam) currentParam += ' ' + leftColText;
              else currentParam = leftColText;
              if (currentValue) currentValue += ' ' + rightColText;
              else currentValue = rightColText;
            } else if (parts.length === 1) {
              if (startCol < 25) {
                if (currentParam) currentParam += ' ' + parts[0];
                else currentParam = parts[0];
              } else {
                if (currentValue) currentValue += ' ' + parts[0];
                else currentValue = parts[0];
              }
            }
          }
        }
        if (currentParam) specs.push({ parameter: currentParam, value: currentValue });
      }

      // Clean, filter, and add to merged array
      for (const s of specs) {
        const p = s.parameter.replace(/\s+/g, ' ').trim();
        const v = s.value.replace(/\s+/g, ' ').trim();

        // Filter out Hindi/Devanagari characters or translation artifacts
        if (/[\u0900-\u097F]/.test(p) || /[\u0900-\u097F]/.test(v)) continue;

        // Filter out pricing floor policy, buyer-defined addons, and non-technical metadata
        const lowerP = p.toLowerCase();
        const lowerV = v.toLowerCase();
        if (lowerP.includes('floor price') || lowerP.includes('addon') || lowerP.includes('defined by buyer')) continue;
        if (lowerP.includes('े ता') || lowerP.includes('िनधा रत') || lowerP.includes('यूनतम')) continue;

        if (p.includes('Speciﬁcation') || p.includes('Specification') || p.includes('विश') || p.includes('ववरण') || p.includes('का नाम') || p.includes('अनुमत मू य')) continue;
        if (p === 'PRODUCT' || p === 'INFORMATION' || p === 'MATTRESS' || p === 'WARRANTY' || p === 'CERTIFICATIONS') continue;
        if (p.includes('MATERIAL AND') || p.includes('DIMENSIONS PARAMETERS')) continue;
        if (p.includes('Additional Specification Parameters') || p.includes('परे षती') || p.includes('Technical Specifications') || p.includes('Disclaimer') || p.includes('अ वीकरण')) continue;
        if (p === 'Name' || p === 'Name Technical Specifications' || p === 'Bid Requirement (Allowed Values)') continue;
        if (p === 'sections' || p === 'syringe pump' || p === 'tolerance (mm)') continue;
        if (v === 'Specification' || v === 'Name' || v === 'Allowed Values' || v === 'Bid Requirement') continue;
        if (p.length < 3 || v.length < 3) continue;

        const key = p.toLowerCase();
        if (!seen.has(key)) {
          seen.add(key);
          allSpecs.push({ parameter: p, value: v });
        }
      }
    } catch (error) {
      console.error(`[extractSpecsFromPdf] Error processing file ${file}:`, error);
    }
  }

  // Format with sequential serial numbers
  return allSpecs.map((s, idx) => ({
    sr: idx + 1,
    parameter: s.parameter,
    value: s.value
  }));
}

// Helper to launch Puppeteer and print HTML content to PDF in Portrait or Landscape mode
async function generatePdfFile(htmlContent: string, outputPath: string, isLandscape: boolean = false) {
  const browser = await puppeteer.launch({
    headless: true,
    executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || undefined,
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });
  try {
    const page = await browser.newPage();
    await page.setContent(htmlContent, { waitUntil: 'domcontentloaded' });
    await page.pdf({
      path: outputPath,
      format: 'A4',
      landscape: isLandscape,
      printBackground: true,
      margin: {
        top: '0px',
        bottom: '0px',
        left: '0px',
        right: '0px'
      }
    });
  } finally {
    await browser.close();
  }
}

// Helper to compile native Word documents (.docx) using Python letterhead generator
function generateDocxWithPython(
  payload: any,
  docFilePath: string,
  orientation: string = 'portrait'
): boolean {
  const tempJsonPath = path.join(os.tmpdir(), `bid_data_${Date.now()}_${Math.random().toString(36).slice(2)}.json`);
  try {
    fs.writeFileSync(tempJsonPath, JSON.stringify(payload, null, 2), 'utf-8');
    const scriptCandidates = [
      path.join(process.cwd(), 'scripts', 'generate_bid_docx.py'),
      path.join(process.cwd(), 'frontend', 'scripts', 'generate_bid_docx.py'),
      path.resolve(__dirname, '../../../../../../scripts', 'generate_bid_docx.py'),
      path.resolve(process.cwd(), '..', 'frontend', 'scripts', 'generate_bid_docx.py')
    ];
    const scriptPath = scriptCandidates.find(p => fs.existsSync(p));
    if (!scriptPath) {
      console.warn('[generateDocxWithPython] generate_bid_docx.py not found in candidate paths');
      return false;
    }

    const pythonCmd = process.platform === 'win32' ? 'python' : 'python3';
    try { if (fs.existsSync(docFilePath)) fs.unlinkSync(docFilePath); } catch (_) {}

    execSync(`"${pythonCmd}" "${scriptPath}" --input "${tempJsonPath}" --output-bid "${docFilePath}" --orientation "${orientation}"`, {
      encoding: 'utf-8',
      stdio: ['ignore', 'pipe', 'pipe']
    });

    if (fs.existsSync(docFilePath)) {
      console.log(`[generateDocxWithPython] Successfully generated high-fidelity Word documents: ${docFilePath}`);
      return true;
    }
  } catch (err) {
    console.warn('[generateDocxWithPython] Python execution error, falling back to html-to-docx:', err);
  } finally {
    if (fs.existsSync(tempJsonPath)) {
      try { fs.unlinkSync(tempJsonPath); } catch (_) {}
    }
  }
  return false;
}

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  let auth = workflowActor(request, 'generateBids');
  let userRole = auth?.role;
  let username = auth?.username;

  if (!auth) {
    const rawRole = request.headers.get('x-user-role');
    const rawUser = request.headers.get('x-user-username');
    if (rawRole && canPerform(rawRole, 'generateBids')) {
      userRole = canonicalRole(rawRole);
      username = rawUser || 'admin';
    } else {
      return workflowForbidden();
    }
  }

  try {
    const { id } = await params;
    let body: any = {};
    try {
      const text = await request.text();
      if (text && text.trim().length > 0) {
        body = JSON.parse(text);
      }
    } catch (_) {
      body = {};
    }

    // Fetch existing tender details from authoritative Spring Boot backend first, fallback to SQLite
    let tender: any = undefined;
    const backendUrl = process.env.BACKEND_URL || 'http://localhost:8090';

    if (backendUrl) {
      try {
        const authH = request.headers.get('authorization');
        const headers: Record<string, string> = { 'Accept': 'application/json' };
        if (authH) headers['authorization'] = authH;
        const bRes = await fetch(`${backendUrl}/api/tenders/${encodeURIComponent(id)}`, { headers });
        if (bRes.ok) {
          const bData = await bRes.json();
          tender = bData.tender || bData;
        }
      } catch (_) {}
    }

    if (!tender) {
      try {
        const tenderStmt = db.prepare('SELECT * FROM tenders WHERE id = ?');
        tender = tenderStmt.get(id) as Tender | undefined;
      } catch (_) {}
    }

    if (!tender) {
      return NextResponse.json(
        { success: false, error: 'Tender not found' },
        { status: 404 }
      );
    }

    // Keep SQLite synchronized with the authoritative tender record
    try {
      const specStatusVal = tender.spec_verification_status || tender.specVerificationStatus || 'None';
      const tpcPriceVal = tender.tpc_purchase_price ?? tender.tpcPurchasePrice ?? null;
      const misPriceVal = tender.mis_final_price ?? tender.misFinalPrice ?? null;
      const currentStageVal = tender.current_stage || tender.currentStage || null;
      db.prepare(`
        UPDATE tenders 
        SET spec_verification_status = COALESCE(?, spec_verification_status),
            tpc_purchase_price = COALESCE(?, tpc_purchase_price),
            mis_final_price = COALESCE(?, mis_final_price),
            current_stage = COALESCE(?, current_stage)
        WHERE id = ?
      `).run(specStatusVal, tpcPriceVal, misPriceVal, currentStageVal, id);
    } catch (_) {}

    // Merge body with tender metadata fallbacks
    const specStatus = tender.spec_verification_status || tender.specVerificationStatus;
    if (specStatus !== 'Approved') {
      return NextResponse.json({
        success: false,
        error: 'Specification clearance is required before the bid package. Commercial pricing happens later, after EMD.'
      }, { status: 409 });
    }

    const companyKey = (body.companyKey || (body.companyName?.toLowerCase().includes('healthtech') ? 'healthtech' : 'me')).toLowerCase();
    const orientation = ((body.orientation || '').toLowerCase() === 'landscape') ? 'landscape' : 'portrait';
    const isLandscape = orientation === 'landscape';

    const defaultCompany = companyKey === 'healthtech' ? {
      companyName: 'Marken Healthtech Limited',
      companyAddress: '93/1 Street No.17, MIDC, Satpur, Nashik- 422007. MH. India',
      companyEmail: 'info@markenworld.com',
      companyWebsite: 'www.markenworld.com',
      companyContact: '+91 91 3030 5959',
      manufacturerName: 'Marken Healthtech Ltd',
      manufacturerAddress: 'Shed No.1, Plot No.93/1, Street No.17, Satpur MIDC, Nashik-422007, Maharashtra.',
      signatoryName: 'Korra Praveen Naik',
      signatoryDesignation: 'Authorized Signatory',
      signatoryAddress: '1-1-51/46, Kapra, ECIL post, S.T.Colony, VTC: Ranga Reddy, District: Hyderabad, State: Andhra Pradesh, PIN Code: 500062',
      witnessDetails: 'Mr. Shreedhar Shingare (Cell No.: 09011104332)',
      localContentPercentage: '100%',
      localContentLocation: 'Shed No.1, Plot No.93/1, Street No.17, Satpur MIDC, Nashik-422007, Maharashtra',
      preferencePolicy: 'PPP MII 2017',
      place: 'Nashik'
    } : {
      companyName: 'Mark Enterprises',
      companyAddress: 'Shed No. 1, Plot No. 93/2, Street No. 17, MIDC Satpur, Nashik – 422007, Maharashtra, India',
      companyEmail: 'info@markworld.com',
      companyWebsite: 'www.markenworld.com',
      companyContact: '09175559646 / +91 90111 04332',
      manufacturerName: 'M/s. Mark Enterprises',
      manufacturerAddress: 'Shed No.1, Plot No.93/2, Street No.17, Satpur MIDC, Nashik-422007, Maharashtra',
      signatoryName: 'Shreedhar Shingare',
      signatoryDesignation: 'Authorized Signatory – Tender manager',
      signatoryAddress: 'Shed No. 1, Plot No. 93/2, Street No. 17, MIDC Satpur, Nashik – 422007, Maharashtra, India',
      witnessDetails: 'Mr. Korra Praveen Naik',
      localContentPercentage: '100%',
      localContentLocation: 'Shed No.1, Plot No.93/2, Street No.17, Satpur MIDC, Nashik-422007, Maharashtra',
      preferencePolicy: 'PPP MII 2017',
      place: 'Nashik'
    };

    const effectiveBidNumber = body.bidNumber || tender.ref_no || tender.refNo || id;
    const rawBidDate = body.bidDate || tender.publish_date || tender.publishDate || new Date().toISOString();
    const effectiveBidDate = formatDate(rawBidDate);
    const effectiveDate = formatDate(body.date || rawBidDate);
    const effectiveQty = body.qty || tender.bid_qty || tender.bidQty || '-';
    const effectiveAuthority = body.authorityName || tender.authority || 'The Competent Authority';
    const effectiveDept = body.authorityDept || '';
    const effectiveAddress = body.authorityAddress || tender.location || '';
    const effectiveProdDesc = body.productDescription || tender.product_name_as_per_tender || tender.productNameAsPerTender || tender.title || 'Equipment / Goods';
    const effectiveProdName = body.productName || tender.product_name_as_per_marken || tender.productNameAsPerMarken || effectiveProdDesc;

    const templateData = {
      tenderId: id,
      bidNumber: effectiveBidNumber,
      qty: effectiveQty,
      productDescription: effectiveProdDesc,
      productName: effectiveProdName,
      authorityName: effectiveAuthority,
      authorityDept: effectiveDept,
      authorityAddress: effectiveAddress,
      offeredMake: body.offeredMake || (companyKey === 'healthtech' ? 'MarkEn' : 'MarkEn'),
      offeredModel: body.offeredModel || '-',
      scheduleNo: body.scheduleNo || '',
      companyKey,
      orientation,
      companyName: body.companyName || defaultCompany.companyName,
      companyAddress: body.companyAddress || defaultCompany.companyAddress,
      companyEmail: body.companyEmail || defaultCompany.companyEmail,
      companyWebsite: body.companyWebsite || defaultCompany.companyWebsite,
      companyContact: body.companyContact || defaultCompany.companyContact,
      signatoryName: body.signatoryName || defaultCompany.signatoryName,
      signatoryDesignation: body.signatoryDesignation || defaultCompany.signatoryDesignation,
      ...body,
      bidDate: effectiveBidDate,
      date: effectiveDate
    };

    const docDir = path.join(process.cwd(), 'public', 'documents', id);
    if (!fs.existsSync(docDir)) {
      fs.mkdirSync(docDir, { recursive: true });
    }

    // 1. Generate and save PDF using Puppeteer
    const pdfFileName = `Bid_Documents_${id}.pdf`;
    const pdfFilePath = path.join(docDir, pdfFileName);
    const pdfDownloadPath = localDocumentUrl(id, pdfFileName);
    const htmlContent = generateHtmlTemplates(templateData);
    await generatePdfFile(htmlContent, pdfFilePath, isLandscape);

    // 2. Generate and save Technical Specification Sheet PDF in Portrait mode (initial stage)
    const specs = extractSpecsFromPdf(id);
    const specHtml = generateTechnicalSpecificationHtml(templateData, specs);

    const specPdfFileName = `Technical_Specification_Sheet_${id}.pdf`;
    const specPdfFilePath = path.join(docDir, specPdfFileName);
    const specPdfDownloadPath = localDocumentUrl(id, specPdfFileName);
    await generatePdfFile(specHtml, specPdfFilePath, false);

    // 3. Generate Word documents (.docx)
    const docFileName = `Bid_Documents_${id}.docx`;
    const docFilePath = path.join(docDir, docFileName);
    const docDownloadPath = localDocumentUrl(id, docFileName);

    const specDocFileName = `Technical_Specification_Sheet_${id}.docx`;
    const specDocFilePath = path.join(docDir, specDocFileName);
    const specDocDownloadPath = localDocumentUrl(id, specDocFileName);

    const isHealthtech = companyKey === 'healthtech';
    const docxHeaderHtml = getDocxHeaderHtml(companyKey);
    const docxFooterHtml = getDocxFooterHtml(companyKey);

    const pythonSuccess = generateDocxWithPython(templateData, docFilePath, orientation);

    if (!pythonSuccess) {
      console.log(`[API Generate Bid Docs] Python generator bypassed/failed. Executing HTMLtoDOCX fallback.`);
      const bidDocxOptions = {
        table: { row: { cantSplit: true } },
        header: true,
        footer: true,
        orientation: isLandscape ? 'landscape' as const : 'portrait' as const,
        pageSize: isLandscape ? {
          width: 16838,
          height: 11906
        } : {
          width: 11906,
          height: 16838
        },
        font: isHealthtech ? 'Cambria' : 'Times New Roman',
        fontSize: isHealthtech ? 20 : 24, // 10pt Cambria vs 12pt Times New Roman
        margins: {
          top: 600,
          bottom: 850,
          left: isHealthtech ? 800 : 650,
          right: isHealthtech ? 800 : 650,
          header: 280,
          footer: 280
        }
      };

      const docxRaw = await HTMLtoDOCX(cleanHtmlForDocx(htmlContent, companyKey), docxHeaderHtml, bidDocxOptions, docxFooterHtml);
      const docxBuffer = await sanitizeDocxBuffer(docxRaw);
      fs.writeFileSync(docFilePath, docxBuffer);
    }

    // Generate Technical Specification Sheet Word file (.docx) in Portrait mode (initial stage)
    const specDocxOptions = {
      table: { row: { cantSplit: true } },
      header: true,
      footer: true,
      orientation: 'portrait' as const,
      pageSize: {
        width: 11906,
        height: 16838
      },
      font: 'Cambria',
      fontSize: 22,
      margins: {
        top: 600,
        bottom: 600,
        left: 650,
        right: 650,
        header: 280,
        footer: 280
      }
    };
    const specDocxRaw = await HTMLtoDOCX(cleanHtmlForDocx(specHtml, companyKey), docxHeaderHtml, specDocxOptions, docxFooterHtml);
    const specDocxBuffer = await sanitizeDocxBuffer(specDocxRaw);
    fs.writeFileSync(specDocFilePath, specDocxBuffer);

    // 4. Update database downloaded_docs metadata
    const bidDocsMeta = {
      name: "Generated Bid Documents (Word DOCX)",
      filename: docFileName,
      local_path: docDownloadPath,
      created_date: new Date().toLocaleDateString('en-IN')
    };

    const bidPdfMeta = {
      name: "Generated Bid Documents (PDF)",
      filename: pdfFileName,
      local_path: pdfDownloadPath,
      created_date: new Date().toLocaleDateString('en-IN')
    };

    const specDocsMeta = {
      name: "Technical Specification Sheet (Word DOCX)",
      filename: specDocFileName,
      local_path: specDocDownloadPath,
      created_date: new Date().toLocaleDateString('en-IN')
    };

    const specPdfMeta = {
      name: "Technical Specification Sheet (PDF)",
      filename: specPdfFileName,
      local_path: specPdfDownloadPath,
      created_date: new Date().toLocaleDateString('en-IN')
    };

    let currentDocs = [];
    try {
      const rawDocs = tender.downloaded_docs || tender.downloadedDocs || '[]';
      currentDocs = typeof rawDocs === 'string' ? JSON.parse(rawDocs) : (Array.isArray(rawDocs) ? rawDocs : []);
      if (!Array.isArray(currentDocs)) currentDocs = [];
    } catch (e) {
      currentDocs = [];
    }

    // Filter out previous versions of both bid documents and specification sheets to prevent duplicates
    currentDocs = currentDocs.filter((d: any) => 
      d.local_path !== docDownloadPath && 
      d.local_path !== pdfDownloadPath && 
      d.local_path !== specDocDownloadPath && 
      d.local_path !== specPdfDownloadPath
    );
    currentDocs.push(bidDocsMeta);
    currentDocs.push(bidPdfMeta);
    currentDocs.push(specDocsMeta);
    currentDocs.push(specPdfMeta);

    const emdValue = tender.emd ?? tender.emdAmount;
    const emdExempt = tender.emd_applicable === false || tender.emdApplicable === false
      || (emdValue != null && Number(emdValue) === 0)
      || tender.payment_status === 'Exempt' || tender.paymentStatus === 'Exempt'
      || tender.emd_status === 'EXEMPT_SKIPPED';
    const nextStage = emdExempt ? 'DUAL_TRACK' : 'EMD_PAYMENT';
    const nextPayment = emdExempt ? 'Exempt' : (tender.payment_status || tender.paymentStatus || 'None');

    // Bid package is step 3. EMD is step 4. Document verification waits until after EMD.
    try {
      const updateStmt = db.prepare(`
        UPDATE tenders 
        SET downloaded_docs = ?, 
            current_stage = ?,
            payment_status = CASE WHEN ? = 'Exempt' THEN 'Exempt' ELSE payment_status END
        WHERE id = ?
      `);
      updateStmt.run(JSON.stringify(currentDocs), nextStage, nextPayment, id);
    } catch (_) {}

    // Attempt notifying Spring Boot backend if available
    if (backendUrl) {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 10000);
      try {
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        const authH = request.headers.get('authorization');
        if (authH) headers['authorization'] = authH;
        const roleH = request.headers.get('x-user-role');
        if (roleH) headers['x-user-role'] = roleH;
        else if (userRole) headers['x-user-role'] = userRole;
        const userH = request.headers.get('x-user-username');
        if (userH) headers['x-user-username'] = userH;
        else if (username) headers['x-user-username'] = username;

        const docsJsonStr = JSON.stringify(currentDocs);
        const isAlreadyApproved = tender.payment_status === 'Approved' || tender.paymentStatus === 'Approved';
        const paymentStatusToPatch = isAlreadyApproved ? undefined : (emdExempt ? 'Exempt' : undefined);

        const patchPayload: Record<string, any> = {
          downloadedDocs: docsJsonStr,
          downloaded_docs: docsJsonStr,
          current_stage: nextStage,
          currentStage: nextStage
        };
        if (paymentStatusToPatch) {
          patchPayload.payment_status = paymentStatusToPatch;
          patchPayload.paymentStatus = paymentStatusToPatch;
        }

        const patchRes = await fetch(`${backendUrl}/api/tenders/${id}`, {
          method: 'PATCH',
          headers,
          body: JSON.stringify(patchPayload),
          signal: controller.signal,
          cache: 'no-store'
        });

        if (!patchRes.ok) {
          const errText = await patchRes.text().catch(() => '');
          console.error(`[generate-bid-docs] Backend PATCH returned ${patchRes.status}: ${errText}`);
        } else {
          console.log(`[generate-bid-docs] Backend successfully updated tender ${id} with bid docs`);
        }
      } catch (patchErr) {
        console.error('[generate-bid-docs] Failed to notify backend:', patchErr);
      } finally {
        clearTimeout(timeout);
      }
    }

    // Log the activity to activity_log
    try {
      addActivityLog(username || 'executive', userRole || 'Tender Executive', 'Generated Bid Documents', id, 'Generated the bid package. Next step is EMD, or dual track when EMD is exempt.');
    } catch (_) {}

    return NextResponse.json({
      success: true,
      message: emdExempt
        ? 'Bid package generated. EMD is exempt, so pricing and document verification are now open.'
        : 'Bid package generated. Enter EMD payment details next. Pricing starts after EMD.',
      downloadUrl: docDownloadPath,
      pdfDownloadUrl: pdfDownloadPath,
      specDownloadUrl: specDocDownloadPath,
      specPdfDownloadUrl: specPdfDownloadPath,
      currentStage: nextStage,
      paymentStatus: nextPayment,
      downloadedDocs: JSON.stringify(currentDocs),
      orientation,
      status: tender.status
    });

  } catch (error: any) {
    console.error('[API Generate Bid Docs] Error generating documents:', error);
    return NextResponse.json(
      { success: false, error: error.message || 'Failed to generate documents.' },
      { status: 500 }
    );
  }
}
