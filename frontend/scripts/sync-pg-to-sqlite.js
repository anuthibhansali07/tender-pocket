const Database = require('better-sqlite3');
const path = require('path');

(async () => {
  try {
    const dbPath = path.join(__dirname, '..', 'tenders.db');
    const db = new Database(dbPath);

    const backendUrl = process.env.BACKEND_URL || (process.env.SPRING_PORT ? `http://localhost:${process.env.SPRING_PORT}` : 'http://localhost:8090');
    console.log(`Connecting to Spring Boot backend at ${backendUrl}...`);
    const adminPwd = process.env.ADMIN_PASSWORD || process.env.ADMIN_DEFAULT_PASSWORD || 'Marken@123$';
    const loginRes = await fetch(`${backendUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: adminPwd })
    });
    const loginData = await loginRes.json();
    if (!loginData.success) {
      throw new Error('Login failed: ' + JSON.stringify(loginData));
    }

    console.log('Fetching tenders from PostgreSQL via Spring Boot...');
    const tendersRes = await fetch(`${backendUrl}/api/tenders`, {
      headers: { 'Authorization': 'Bearer ' + loginData.token }
    });
    const { tenders } = await tendersRes.json();
    console.log(`Fetched ${tenders.length} tenders from PostgreSQL.`);

    const insert = db.prepare(`
      INSERT OR REPLACE INTO tenders (
        id, ref_no, title, authority, estimated_cost, emd, location, sector,
        due_date, opening_date, document_url, original_url, status, notes,
        scraped_at, entry_date, mis_executive, source, source_id, publish_date,
        payment_status, verification_status, submission_status, outcome_status, spec_verification_status
      ) VALUES (
        @id, @refNo, @title, @authority, @estimatedCost, @emd, @location, @sector,
        @dueDate, @openingDate, @documentUrl, @originalUrl, @status, @notes,
        @scrapedAt, @entryDate, @misExecutive, @source, @sourceId, @publishDate,
        @paymentStatus, @verificationStatus, @submissionStatus, @outcomeStatus, @specVerificationStatus
      )
    `);

    const insertMany = db.transaction((list) => {
      for (const t of list) {
        insert.run({
          id: t.id || '',
          refNo: t.refNo || '',
          title: t.title || 'Untitled',
          authority: t.authority || '',
          estimatedCost: t.estimatedCost || 0,
          emd: t.emd || 0,
          location: t.location || '',
          sector: t.sector || '',
          dueDate: t.dueDate || '',
          openingDate: t.openingDate || '',
          documentUrl: t.documentUrl || '',
          originalUrl: t.originalUrl || '',
          status: t.status || 'Issued',
          notes: t.notes || '',
          scrapedAt: t.scrapedAt || new Date().toISOString(),
          entryDate: t.entryDate || '',
          misExecutive: t.misExecutive || '',
          source: t.source || 'GeM',
          sourceId: t.sourceId || '',
          publishDate: t.publishDate || '',
          paymentStatus: t.paymentStatus || 'None',
          verificationStatus: t.verificationStatus || 'None',
          submissionStatus: t.submissionStatus || 'None',
          outcomeStatus: t.outcomeStatus || 'None',
          specVerificationStatus: t.specVerificationStatus || 'None'
        });
      }
    });

    insertMany(tenders);
    const count = db.prepare('SELECT COUNT(*) as c FROM tenders').get();
    console.log(`SUCCESS! SQLite database now has ${count.c} tenders.`);
  } catch (err) {
    console.error('Sync failed:', err);
  }
})();
