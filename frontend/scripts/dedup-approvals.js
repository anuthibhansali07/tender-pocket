const Database = require('better-sqlite3');
const path = require('path');
const db = new Database(path.join(__dirname, '..', 'tenders.db'));

// Remove duplicate rows (keep lowest ID per tender+stage combination)
const dupes = db.prepare('SELECT MIN(id) as keep_id, tender_id, stage FROM tender_approval_requests WHERE status=? GROUP BY tender_id, stage HAVING COUNT(*) > 1').all('PENDING');
console.log('Duplicate groups:', dupes.length);
for (const d of dupes) {
  const deleted = db.prepare('DELETE FROM tender_approval_requests WHERE tender_id=? AND stage=? AND status=? AND id != ?').run(d.tender_id, d.stage, 'PENDING', d.keep_id);
  console.log('Removed', deleted.changes, 'dupes for tender', d.tender_id, 'stage', d.stage);
}
const total = db.prepare("SELECT COUNT(*) as c FROM tender_approval_requests WHERE status='PENDING'").get();
console.log('Final PENDING count:', total.c);
db.close();
console.log('Done!');
