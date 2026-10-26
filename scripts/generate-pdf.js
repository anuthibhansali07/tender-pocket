const fs = require('fs');
const path = require('path');
const puppeteer = require(path.join(__dirname, '..', 'frontend', 'node_modules', 'puppeteer'));

async function generatePdf() {
  const mdPath = path.join(__dirname, '..', 'WORKFLOW_SYSTEM_ARCHITECTURE_REVIEW.md');
  const pdfPath = path.join(__dirname, '..', 'WORKFLOW_SYSTEM_ARCHITECTURE_REVIEW.pdf');

  if (!fs.existsSync(mdPath)) {
    console.error('Markdown file not found at:', mdPath);
    process.exit(1);
  }

  const mdContent = fs.readFileSync(mdPath, 'utf8');

  // HTML template with Marked & Mermaid CDN
  const htmlTemplate = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>TenderPocket — Detailed Workflow & Architecture Specification</title>
  <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
  <style>
    @page {
      size: A4;
      margin: 18mm 16mm 18mm 16mm;
      @bottom-right {
        content: counter(page);
      }
    }
    
    * {
      box-sizing: border-box;
    }

    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
      font-size: 10.5pt;
      line-height: 1.55;
      color: #1e293b;
      background-color: #ffffff;
      margin: 0;
      padding: 0;
    }

    h1, h2, h3, h4, h5, h6 {
      color: #0f172a;
      font-weight: 700;
      margin-top: 1.4em;
      margin-bottom: 0.5em;
      page-break-after: avoid;
    }

    h1 {
      font-size: 20pt;
      border-bottom: 2.5px solid #3b82f6;
      padding-bottom: 8px;
      margin-top: 0;
      color: #0f172a;
    }

    h2 {
      font-size: 14pt;
      border-bottom: 1.5px solid #e2e8f0;
      padding-bottom: 5px;
      margin-top: 1.6em;
      color: #1e3a8a;
    }

    h3 {
      font-size: 12pt;
      color: #1e40af;
      margin-top: 1.2em;
    }

    h4 {
      font-size: 11pt;
      color: #334155;
    }

    p {
      margin: 0.6em 0;
    }

    /* Metadata block */
    strong {
      color: #0f172a;
    }

    /* Tables */
    table {
      width: 100%;
      border-collapse: collapse;
      margin: 1.2em 0;
      font-size: 8.5pt;
      page-break-inside: auto;
    }

    tr {
      page-break-inside: avoid;
      page-break-after: auto;
    }

    th, td {
      border: 1px solid #cbd5e1;
      padding: 7px 9px;
      text-align: left;
      vertical-align: top;
    }

    th {
      background-color: #f1f5f9;
      color: #0f172a;
      font-weight: 700;
      border-bottom: 2px solid #94a3b8;
    }

    tr:nth-child(even) td {
      background-color: #f8fafc;
    }

    /* Blockquotes & Callouts */
    blockquote {
      margin: 1.2em 0;
      padding: 10px 16px;
      border-left: 4.5px solid #3b82f6;
      background-color: #eff6ff;
      color: #1e3a8a;
      border-radius: 0 6px 6px 0;
      page-break-inside: avoid;
    }

    blockquote p {
      margin: 0;
    }

    /* Code and Preformatted */
    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
      font-size: 9pt;
      background-color: #f1f5f9;
      color: #0f172a;
      padding: 2px 5px;
      border-radius: 4px;
      border: 1px solid #e2e8f0;
    }

    pre {
      background-color: #0f172a;
      color: #f8fafc;
      padding: 12px 14px;
      border-radius: 6px;
      overflow-x: auto;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 8.5pt;
      line-height: 1.45;
      page-break-inside: avoid;
      margin: 1em 0;
    }

    pre code {
      background-color: transparent;
      color: inherit;
      padding: 0;
      border: none;
      font-size: inherit;
    }

    /* Mermaid container */
    .mermaid {
      background-color: #ffffff;
      padding: 14px 10px;
      border-radius: 8px;
      border: 1px solid #e2e8f0;
      box-shadow: 0 1px 3px rgba(0,0,0,0.05);
      margin: 1.4em 0;
      text-align: center;
      page-break-inside: avoid;
    }

    .mermaid svg {
      max-width: 100% !important;
      height: auto !important;
    }

    /* Badges & Pills */
    .badge-green {
      background-color: #dcfce7;
      color: #15803d;
      border: 1px solid #bbf7d0;
      padding: 2px 6px;
      border-radius: 4px;
      font-weight: 600;
    }

    .badge-blue {
      background-color: #dbeafe;
      color: #1d4ed8;
      border: 1px solid #bfdbfe;
      padding: 2px 6px;
      border-radius: 4px;
      font-weight: 600;
    }

    /* Print header & footer */
    .header-rule {
      border-bottom: 2px solid #3b82f6;
      margin-bottom: 20px;
    }

    ul, ol {
      margin: 0.6em 0;
      padding-left: 24px;
    }

    li {
      margin-bottom: 0.3em;
    }

    hr {
      border: none;
      border-top: 1.5px solid #e2e8f0;
      margin: 1.8em 0;
    }
  </style>
</head>
<body>
  <div id="content"></div>

  <script id="md-source" type="text/markdown">${JSON.stringify(mdContent)}</script>

  <script>
    window.addEventListener('DOMContentLoaded', async () => {
      const rawMd = JSON.parse(document.getElementById('md-source').textContent);
      
      // Configure marked
      marked.setOptions({
        gfm: true,
        breaks: false
      });

      const parsedHtml = marked.parse(rawMd);
      const container = document.getElementById('content');
      container.innerHTML = parsedHtml;

      // Find all code blocks with class language-mermaid
      const mermaidCodeBlocks = document.querySelectorAll('pre code.language-mermaid');
      mermaidCodeBlocks.forEach((codeEl) => {
        const pre = codeEl.parentElement;
        const mermaidDiv = document.createElement('div');
        mermaidDiv.className = 'mermaid';
        mermaidDiv.textContent = codeEl.textContent;
        pre.parentNode.replaceChild(mermaidDiv, pre);
      });

      // Initialize mermaid
      mermaid.initialize({
        startOnLoad: false,
        theme: 'default',
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif',
        securityLevel: 'loose',
        flowchart: {
          useMaxWidth: true,
          htmlLabels: true,
          curve: 'basis'
        },
        sequence: {
          useMaxWidth: true,
          showSequenceNumbers: true
        }
      });

      try {
        await mermaid.run();
      } catch (err) {
        console.error('Mermaid render error:', err);
      }

      window.__MERMAID_DONE__ = true;
    });
  </script>
</body>
</html>`;

  console.log('Launching headless browser...');
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu']
  });

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1200, height: 1600 });

    console.log('Setting page content and awaiting network idle...');
    await page.setContent(htmlTemplate, { waitUntil: 'networkidle0', timeout: 30000 });

    console.log('Waiting for Mermaid diagrams to render...');
    await page.waitForFunction(() => window.__MERMAID_DONE__ === true, { timeout: 20000 });

    // Small delay to ensure all SVGs finish layout calculations
    await new Promise(r => setTimeout(r, 1000));

    console.log('Rendering PDF to:', pdfPath);
    await page.pdf({
      path: pdfPath,
      format: 'A4',
      printBackground: true,
      displayHeaderFooter: true,
      headerTemplate: `
        <div style="font-family: -apple-system, sans-serif; font-size: 8pt; color: #94a3b8; width: 100%; display: flex; justify-content: space-between; padding: 0 16mm;">
          <span>TenderPocket — Enterprise Architecture & Detailed Workflow</span>
          <span>Version 2.4.0</span>
        </div>
      `,
      footerTemplate: `
        <div style="font-family: -apple-system, sans-serif; font-size: 8pt; color: #94a3b8; width: 100%; display: flex; justify-content: space-between; padding: 0 16mm;">
          <span>Confidential — Internal Review</span>
          <span>Page <span class="pageNumber"></span> of <span class="totalPages"></span></span>
        </div>
      `,
      margin: {
        top: '22mm',
        bottom: '22mm',
        left: '16mm',
        right: '16mm'
      }
    });

    const stats = fs.statSync(pdfPath);
    console.log(`PDF successfully generated! File size: ${(stats.size / 1024).toFixed(1)} KB`);
  } catch (err) {
    console.error('Failed to generate PDF:', err);
    process.exit(1);
  } finally {
    await browser.close();
  }
}

generatePdf();
