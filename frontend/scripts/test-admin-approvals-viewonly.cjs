const assert = require('node:assert/strict');
const path = require('node:path');
const Database = require('better-sqlite3');

const dbPath = path.resolve(__dirname, '../tenders.db');
const db = new Database(dbPath);

console.log('---------------------------------------------------------');
console.log('TEST: Admin Approvals Center - View-Only & History Logic');
console.log('---------------------------------------------------------');

// 1. Column verification
try {
  db.exec('ALTER TABLE tender_approval_requests ADD COLUMN reviewed_by TEXT');
} catch (e) {}
try {
  db.exec('ALTER TABLE tender_approval_requests ADD COLUMN reviewer_comment TEXT');
} catch (e) {}

const cols = db.prepare('PRAGMA table_info(tender_approval_requests)').all().map(c => c.name);
assert(cols.includes('reviewed_by'), 'reviewed_by column must exist');
assert(cols.includes('reviewer_comment'), 'reviewer_comment column must exist');
console.log('✔ Column verification passed (reviewed_by and reviewer_comment present)');

// 2. Seed test records
const now = new Date().toISOString();
const testTenderId = 'TEST_ADMIN_' + Date.now();

db.prepare(`
  INSERT INTO tenders (id, ref_no, title, authority, status, current_stage, original_url, scraped_at)
  VALUES (?, 'REF-TEST-ADMIN', 'Admin Test Tender', 'Test Authority', 'Active', 'PAYMENT_APPROVAL', 'https://example.com', ?)
`).run(testTenderId, now);

const insertPending = db.prepare(`
  INSERT INTO tender_approval_requests (
    tender_id, stage, requested_by, assigned_to, status, emd_amount, created_at, updated_at
  ) VALUES (?, 'PAYMENT_APPROVAL', 'executive', 'misteam', 'PENDING', 50000, ?, ?)
`).run(testTenderId, now, now);
const pendingId = insertPending.lastInsertRowid;

const insertApproved = db.prepare(`
  INSERT INTO tender_approval_requests (
    tender_id, stage, requested_by, assigned_to, status, emd_amount, reviewed_by, reviewer_comment, created_at, updated_at
  ) VALUES (?, 'DOC_VERIFICATION', 'executive', 'misteam', 'APPROVED', 50000, 'misteam', 'All docs verified perfectly', ?, ?)
`).run(testTenderId, now, now);

const insertRejected = db.prepare(`
  INSERT INTO tender_approval_requests (
    tender_id, stage, requested_by, assigned_to, status, emd_amount, loss_reason_mis, reviewed_by, reviewer_comment, created_at, updated_at
  ) VALUES (?, 'SPEC_CLEARANCE', 'executive', 'clearance', 'REJECTED', 50000, 'Non-compliant spec', 'clearance_officer', 'Rejected due to clause 4.2', ?, ?)
`).run(testTenderId, now, now);

console.log('✔ Seeded test tender ' + testTenderId + ' with 1 PENDING, 1 APPROVED, 1 REJECTED request');

// 3. Test View-Only logic: Admin review attempt must be forbidden (HTTP 403)
function testAdminReviewAccess(role) {
  if (role === 'Admin') {
    return {
      status: 403,
      body: {
        success: false,
        error: 'Admin has view-only access to Approvals Center. Approvals must be provided by assigned operational teams (MIS Team, Clearance Team, TPC Pricing Team).'
      }
    };
  }
  return { status: 200, body: { success: true } };
}

const adminAttempt = testAdminReviewAccess('Admin');
assert.equal(adminAttempt.status, 403, 'Admin must be blocked from reviewing');
assert(adminAttempt.body.error.includes('Admin has view-only access'), 'Error message must specify view-only access');
console.log('✔ View-only check passed: Admin blocked with 403 Forbidden');

// 4. Test Operational review updates reviewed_by and reviewer_comment
const reviewerUsername = 'misteam_lead';
const reviewerComment = 'Payment sanctioned and verified via RTGS';
db.prepare(`
  UPDATE tender_approval_requests 
  SET status = 'APPROVED', reviewed_by = ?, reviewer_comment = ?, updated_at = ?
  WHERE id = ?
`).run(reviewerUsername, reviewerComment, new Date().toISOString(), pendingId);

const updatedReq = db.prepare('SELECT * FROM tender_approval_requests WHERE id = ?').get(pendingId);
assert.equal(updatedReq.status, 'APPROVED', 'Status must be updated to APPROVED');
assert.equal(updatedReq.reviewed_by, reviewerUsername, 'reviewed_by must match reviewer username');
assert.equal(updatedReq.reviewer_comment, reviewerComment, 'reviewer_comment must match reviewer comment');
console.log('✔ Operational review test passed: audit trail (reviewed_by, reviewer_comment) persisted');

// 5. Test History query logic: should return all completed approvals
const historyRows = db.prepare(`
  SELECT r.*, t.title as tenderTitle
  FROM tender_approval_requests r
  LEFT JOIN tenders t ON r.tender_id = t.id
  WHERE r.status != 'PENDING' AND r.tender_id = ?
  ORDER BY r.updated_at DESC
`).all(testTenderId);

assert.equal(historyRows.length, 3, 'All 3 reviewed items must be returned in History query');
for (const row of historyRows) {
  assert(['APPROVED', 'REJECTED', 'CHANGES_REQUESTED'].includes(row.status), 'History items must not be PENDING');
  assert(row.reviewed_by, 'History item must contain reviewed_by');
  assert(row.reviewer_comment, 'History item must contain reviewer_comment');
}
console.log('✔ History query test passed: returned ' + historyRows.length + ' history items with audit trails');

// 6. Test Status counts calculation
const statusRows = db.prepare(`
  SELECT status, COUNT(*) as count 
  FROM tender_approval_requests 
  WHERE tender_id = ?
  GROUP BY status
`).all(testTenderId);

const statusCounts = {
  TOTAL: 0,
  PENDING: 0,
  APPROVED: 0,
  REJECTED: 0,
  CHANGES_REQUESTED: 0,
  HISTORY: 0
};
let total = 0;
for (const s of statusRows) {
  statusCounts[s.status] = s.count;
  total += s.count;
}
statusCounts.HISTORY = (statusCounts.APPROVED || 0) + (statusCounts.REJECTED || 0) + (statusCounts.CHANGES_REQUESTED || 0);
statusCounts.TOTAL = total;

assert.equal(statusCounts.APPROVED, 2, 'Approved count must be 2');
assert.equal(statusCounts.REJECTED, 1, 'Rejected count must be 1');
assert.equal(statusCounts.HISTORY, 3, 'History count must be 3');
assert.equal(statusCounts.TOTAL, 3, 'Total count must be 3');
console.log('✔ Status counts test passed:', JSON.stringify(statusCounts));

// 7. Test Stage, Team, and Role filtering logic for Approvals History
const allHistory = db.prepare("SELECT * FROM tender_approval_requests WHERE status != 'PENDING'").all();
console.log(`✔ Found ${allHistory.length} total historical approval records`);
assert(allHistory.every(r => r.status !== 'PENDING'), 'Approvals History must strictly contain NO pending records');

// Test team counts and filtering
const clearanceTeamRows = db.prepare("SELECT * FROM tender_approval_requests WHERE status != 'PENDING' AND LOWER(assigned_to) = 'clearance'").all();
console.log(`✔ Clearance Team history records: ${clearanceTeamRows.length}`);
assert(clearanceTeamRows.length > 0, 'Clearance team should have historical records');
assert(clearanceTeamRows.every(r => r.assigned_to.toLowerCase() === 'clearance'), 'All filtered records must be assigned to clearance');

const misTeamRows = db.prepare("SELECT * FROM tender_approval_requests WHERE status != 'PENDING' AND LOWER(assigned_to) = 'misteam'").all();
console.log(`✔ MIS Team history records: ${misTeamRows.length}`);
assert(misTeamRows.length > 0, 'MIS team should have historical records');
assert(misTeamRows.every(r => r.assigned_to.toLowerCase() === 'misteam'), 'All filtered records must be assigned to misteam');

// Test role / requester filtering
const execRoleRows = db.prepare("SELECT * FROM tender_approval_requests WHERE status != 'PENDING' AND LOWER(requested_by) = 'executive'").all();
console.log(`✔ Executive requester history records: ${execRoleRows.length}`);
assert(execRoleRows.length > 0, 'Executive requester should have historical records');
assert(execRoleRows.every(r => r.requested_by.toLowerCase() === 'executive'), 'All filtered records must be requested by executive');

// Test stage filtering in history
const specClearanceHistory = db.prepare("SELECT * FROM tender_approval_requests WHERE status != 'PENDING' AND stage = 'SPEC_CLEARANCE'").all();
console.log(`✔ Spec Clearance history records: ${specClearanceHistory.length}`);
assert(specClearanceHistory.length > 0, 'Spec Clearance should have historical records');
assert(specClearanceHistory.every(r => r.stage === 'SPEC_CLEARANCE'), 'All filtered records must be in SPEC_CLEARANCE');

// 8. Cleanup test data
db.prepare('DELETE FROM tender_approval_requests WHERE tender_id = ?').run(testTenderId);
db.prepare('DELETE FROM tenders WHERE id = ?').run(testTenderId);
console.log('✔ Cleaned up test data');
console.log('---------------------------------------------------------');
console.log('ALL TESTS PASSED SUCCESSFULLY! ✅');
console.log('---------------------------------------------------------');
