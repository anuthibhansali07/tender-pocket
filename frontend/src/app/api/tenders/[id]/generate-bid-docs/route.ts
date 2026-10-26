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

interface ExtractedTenderResult {
  specs: { sr: number, parameter: string, value: string, compliance?: string, offered?: string, deviation?: string, isAtc?: boolean }[];
  metadata?: {
    bidNumber?: string;
    bidDate?: string;
    qty?: string;
    authorityName?: string;
    authorityDept?: string;
    authorityAddress?: string;
    productDescription?: string;
  };
}

function extractTenderInfoAndSpecs(id: string): ExtractedTenderResult {
  // 1. Try high-accuracy PyMuPDF python extractor
  try {
    const pythonCmd = process.platform === 'win32' ? 'python' : 'python3';
    const scriptCandidates = [
      path.join(process.cwd(), 'scripts', 'extract_tender_specs.py'),
      path.join(process.cwd(), 'frontend', 'scripts', 'extract_tender_specs.py'),
      path.resolve(__dirname, '../../../../../../scripts', 'extract_tender_specs.py'),
      path.resolve(process.cwd(), '..', 'frontend', 'scripts', 'extract_tender_specs.py')
    ];
    const scriptPath = scriptCandidates.find(p => fs.existsSync(p));
    if (scriptPath) {
      const out = execSync(`"${pythonCmd}" "${scriptPath}" --tender-id "${id}"`, {
        encoding: 'utf-8',
        stdio: ['ignore', 'pipe', 'pipe'],
        timeout: 20000
      });
      const jsonStart = out.indexOf('{');
      const jsonEnd = out.lastIndexOf('}');
      if (jsonStart !== -1 && jsonEnd !== -1) {
        const parsed = JSON.parse(out.slice(jsonStart, jsonEnd + 1));
        if (parsed.success && Array.isArray(parsed.specs) && parsed.specs.length > 0) {
          return {
            specs: parsed.specs,
            metadata: {
              bidNumber: parsed.bidNumber,
              bidDate: parsed.bidDate,
              qty: parsed.qty,
              authorityName: parsed.authorityName,
              authorityDept: parsed.authorityDept,
              authorityAddress: parsed.authorityAddress,
              productDescription: parsed.productDescription
            }
          };
        }
      }
    }
  } catch (err) {
    console.warn(`[extractTenderInfoAndSpecs] Python extraction error for tender ${id}:`, err);
  }

  // 2. Fallback: Search across candidate directories for the authentic tender PDF
  const candidateDirs = [
    path.join(process.cwd(), '..', 'backend', 'public', 'documents', id),
    path.join(process.cwd(), 'backend', 'public', 'documents', id),
    path.join(process.cwd(), 'public', 'documents', id),
    path.join(process.cwd(), '..', 'public', 'documents', id)
  ];

  let docDir = candidateDirs.find(d => fs.existsSync(d));
  if (!docDir) {
    return { specs: [] };
  }

  const files = fs.readdirSync(docDir).filter(f => f.toLowerCase().endsWith('.pdf') && !f.startsWith('Bid_Documents_') && !f.startsWith('Technical_Specification_Sheet_'));
  const allSpecs: { sr: number, parameter: string, value: string, compliance: string, offered: string, deviation: string, isAtc: boolean }[] = [];
  const seen = new Set<string>();

  for (const file of files) {
    const pdfPath = path.join(docDir, file);
    try {
      const text = extractRawTextFromPdf(pdfPath);
      if (!text || text.trim().length < 50) continue;

      const lines = text.split('\n');
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || /^\d+\s*\/\s*\d+$/.test(trimmed)) continue;
        if (trimmed.includes('Technical Specifications') || trimmed.includes('Consignee') || trimmed.includes('View File')) continue;
        const parts = line.split(/\s{3,}/).map(p => p.trim()).filter(Boolean);
        if (parts.length >= 2) {
          const p = parts[0];
          const v = parts.slice(1).join(' ');
          if (p.length > 2 && v.length > 0 && !seen.has(p.toLowerCase())) {
            seen.add(p.toLowerCase());
            allSpecs.push({
              sr: allSpecs.length + 1,
              parameter: p,
              value: v,
              compliance: 'Yes',
              offered: v,
              deviation: 'No Deviation',
              isAtc: false
            });
          }
        }
      }
    } catch (_) {}
  }

  return { specs: allSpecs };
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
  specDocFilePath: string,
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
    // Clean up destination files first so stale files from prior runs don't cause false positives
    try { if (fs.existsSync(docFilePath)) fs.unlinkSync(docFilePath); } catch (_) {}
    try { if (fs.existsSync(specDocFilePath)) fs.unlinkSync(specDocFilePath); } catch (_) {}

    execSync(`"${pythonCmd}" "${scriptPath}" --input "${tempJsonPath}" --output-bid "${docFilePath}" --output-spec "${specDocFilePath}" --orientation "${orientation}"`, {
      encoding: 'utf-8',
      stdio: ['ignore', 'pipe', 'pipe']
    });

    if (fs.existsSync(docFilePath) && fs.existsSync(specDocFilePath)) {
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
    const misFinalPrice = tender.mis_final_price ?? tender.misFinalPrice;
    if (specStatus !== 'Approved' || misFinalPrice === undefined || misFinalPrice === null || !Number.isFinite(Number(misFinalPrice)) || Number(misFinalPrice) <= 0) {
      return NextResponse.json({
        success: false,
        error: 'Specification clearance and finalized MIS pricing are required before generating bid documents.'
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

    // Extract authentic technical specifications & tender metadata from tender PDF
    const { specs, metadata } = extractTenderInfoAndSpecs(id);

    const effectiveBidNumber = body.bidNumber || tender.ref_no || tender.refNo || metadata?.bidNumber || id;
    const rawBidDate = body.bidDate || metadata?.bidDate || tender.publish_date || tender.publishDate || new Date().toISOString();
    const effectiveBidDate = formatDate(rawBidDate);
    const effectiveDate = formatDate(body.date || rawBidDate);
    const effectiveQty = body.qty || tender.bid_qty || tender.bidQty || metadata?.qty || '-';
    const effectiveAuthority = body.authorityName || tender.authority || metadata?.authorityName || 'The Competent Authority';
    const effectiveDept = body.authorityDept || metadata?.authorityDept || '';
    const effectiveAddress = body.authorityAddress || tender.location || metadata?.authorityAddress || '';
    const effectiveProdDesc = body.productDescription || tender.product_name_as_per_tender || tender.productNameAsPerTender || metadata?.productDescription || tender.title || 'Equipment / Goods';
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

    const finalSpecs = (specs && specs.length > 0) ? specs : [
      {
        sr: 1,
        parameter: effectiveProdDesc,
        value: `As per Bid Document / Tender Requirement${effectiveQty !== '-' ? ` (Qty: ${effectiveQty})` : ''}`,
        compliance: 'Yes',
        offered: `Fully compliant with all technical specifications and tender requirements for ${effectiveProdDesc}`,
        deviation: 'No Deviation',
        isAtc: false
      }
    ];

    const enrichedTemplateData = {
      ...templateData,
      specs: finalSpecs
    };

    // 1. Generate and save PDF using Puppeteer
    const pdfFileName = `Bid_Documents_${id}.pdf`;
    const pdfFilePath = path.join(docDir, pdfFileName);
    const pdfDownloadPath = localDocumentUrl(id, pdfFileName);
    const htmlContent = generateHtmlTemplates(enrichedTemplateData);
    await generatePdfFile(htmlContent, pdfFilePath, isLandscape);

    // 2. Generate and save Technical Specification Sheet PDF (ALWAYS LANDSCAPE)
    const specPdfFileName = `Technical_Specification_Sheet_${id}.pdf`;
    const specPdfFilePath = path.join(docDir, specPdfFileName);
    const specPdfDownloadPath = localDocumentUrl(id, specPdfFileName);
    const specHtml = generateTechnicalSpecificationHtml(enrichedTemplateData, finalSpecs);
    // Technical Specification Sheet MUST ALWAYS be generated in landscape mode!
    await generatePdfFile(specHtml, specPdfFilePath, true);

    // 3. Generate Word documents (.docx) using high-fidelity Python letterhead generator
    const docFileName = `Bid_Documents_${id}.docx`;
    const docFilePath = path.join(docDir, docFileName);
    const docDownloadPath = localDocumentUrl(id, docFileName);

    const specDocFileName = `Technical_Specification_Sheet_${id}.docx`;
    const specDocFilePath = path.join(docDir, specDocFileName);
    const specDocDownloadPath = localDocumentUrl(id, specDocFileName);

    const pythonSuccess = generateDocxWithPython(enrichedTemplateData, docFilePath, specDocFilePath, orientation);

    if (!pythonSuccess) {
      console.log(`[API Generate Bid Docs] Python generator bypassed/failed. Executing HTMLtoDOCX fallback.`);
      const isHealthtech = companyKey === 'healthtech';
      const docxHeaderHtml = getDocxHeaderHtml(companyKey);
      const docxFooterHtml = getDocxFooterHtml(companyKey);

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

      const specDocxOptions = {
        table: { row: { cantSplit: true } },
        header: true,
        footer: true,
        orientation: 'landscape' as const,
        pageSize: {
          width: 16838,
          height: 11906
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

      const docxRaw = await HTMLtoDOCX(cleanHtmlForDocx(htmlContent, companyKey), docxHeaderHtml, bidDocxOptions, docxFooterHtml);
      const docxBuffer = await sanitizeDocxBuffer(docxRaw);
      fs.writeFileSync(docFilePath, docxBuffer);

      const specDocxRaw = await HTMLtoDOCX(cleanHtmlForDocx(specHtml, companyKey), docxHeaderHtml, specDocxOptions, docxFooterHtml);
      const specDocxBuffer = await sanitizeDocxBuffer(specDocxRaw);
      fs.writeFileSync(specDocFilePath, specDocxBuffer);
    }

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

    // Update downloaded_docs metadata and advance stage to DOC_VERIFICATION in SQLite if available
    try {
      const updateStmt = db.prepare(`
        UPDATE tenders 
        SET downloaded_docs = ?, 
            current_stage = 'DOC_VERIFICATION', 
            verification_status = 'Pending',
            payment_status = 'None',
            submission_status = 'None'
        WHERE id = ?
      `);
      updateStmt.run(JSON.stringify(currentDocs), id);
    } catch (_) {}

    const now = new Date().toISOString();
    const assignedMis = tender.assigned_mis_member || tender.assignedMisMember || 'misteam';

    try {
      // Cancel/remove any premature downstream approval requests
      db.prepare("DELETE FROM tender_approval_requests WHERE tender_id = ? AND stage IN ('PAYMENT_APPROVAL', 'SUBMISSION_PENDING', 'WIN_LOSS_PENDING') AND status = 'PENDING'").run(id);

      const existingDocReq = db.prepare("SELECT id FROM tender_approval_requests WHERE tender_id = ? AND stage = 'DOC_VERIFICATION' AND status = 'PENDING'").get(id);
      if (existingDocReq) {
        db.prepare("UPDATE tender_approval_requests SET requested_by = ?, assigned_to = ?, working_path = ?, updated_at = ? WHERE id = ?")
          .run(username, assignedMis, docDownloadPath, now, (existingDocReq as any).id);
      } else {
        db.prepare(`
          INSERT INTO tender_approval_requests (tender_id, stage, requested_by, assigned_to, working_path, status, created_at, updated_at)
          VALUES (?, 'DOC_VERIFICATION', ?, ?, ?, 'PENDING', ?, ?)
        `).run(id, username, assignedMis, docDownloadPath, now, now);
      }
    } catch (dbErr) {
      console.error('[API Generate Bid Docs] Error creating approval request:', dbErr);
    }

    // Attempt notifying Spring Boot backend if available
    if (backendUrl) {
      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 2000);
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        const authH = request.headers.get('authorization');
        if (authH) headers['authorization'] = authH;

        const docsJsonStr = JSON.stringify(currentDocs);

        fetch(`${backendUrl}/api/tenders/${id}/doc-verification-request`, {
          method: 'POST',
          headers,
          body: JSON.stringify({
            assignedMisExecutive: assignedMis,
            workingPath: docDownloadPath,
            downloadedDocs: docsJsonStr,
            downloaded_docs: docsJsonStr,
            comment: 'Bid document package generated. Submitted for MIS verification & approval.'
          }),
          signal: controller.signal,
          cache: 'no-store'
        }).catch(() => {});

        fetch(`${backendUrl}/api/tenders/${id}`, {
          method: 'PATCH',
          headers,
          body: JSON.stringify({
            downloadedDocs: docsJsonStr,
            downloaded_docs: docsJsonStr,
            current_stage: 'DOC_VERIFICATION',
            verification_status: 'Pending'
          }),
          signal: controller.signal,
          cache: 'no-store'
        }).catch(() => {});

        clearTimeout(timeout);
      } catch (_) {}
    }

    // Log the activity to activity_log
    try {
      addActivityLog(username || 'executive', userRole || 'Tender Executive', 'Generated Bid Documents', id, 'Generated Word & PDF bid document package and Technical Specification Sheet. Submitted to MIS Team for approval.');
    } catch (_) {}

    console.log(`[API Generate Bid Docs] Compiled Word (.docx) and PDF (.pdf) successfully for Tender ${id}. Advanced stage to DOC_VERIFICATION.`);

    return NextResponse.json({
      success: true,
      message: 'Bid Documents generated successfully and forwarded to MIS Team for approval.',
      downloadUrl: docDownloadPath,
      pdfDownloadUrl: pdfDownloadPath,
      specDownloadUrl: specDocDownloadPath,
      specPdfDownloadUrl: specPdfDownloadPath,
      currentStage: 'DOC_VERIFICATION',
      verificationStatus: 'Pending',
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
