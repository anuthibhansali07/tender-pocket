const Database = require('better-sqlite3');
const path = require('path');

const dbPath = path.join(__dirname, '..', 'tenders.db');
const db = new Database(dbPath);

console.log('Seeding test approvals and unlocking sample tenders...');

// 1. Unlock spec verification status on sample tenders
db.prepare(`
  UPDATE tenders 
  SET spec_verification_status = 'Approved', 
      status = 'Participating', 
      estimated_cost = 450000, 
      emd = 25000 
  WHERE id = '9849390'
`).run();

db.prepare(`
  UPDATE tenders 
  SET spec_verification_status = 'Approved', 
      status = 'Participating', 
      estimated_cost = 320000, 
      emd = 16000 
  WHERE id = '9712000'
`).run();

db.prepare(`
  UPDATE tenders 
  SET spec_verification_status = 'Approved', 
      status = 'Participating', 
      estimated_cost = 890000, 
      emd = 44500 
  WHERE id = '8715358'
`).run();

db.prepare(`
  UPDATE tenders 
  SET spec_verification_status = 'Approved', 
      status = 'Participating', 
      estimated_cost = 210000, 
      emd = 10500 
  WHERE id = '9808508'
`).run();

db.prepare(`
  UPDATE tenders 
  SET spec_verification_status = 'Pending', 
      status = 'Participating', 
      estimated_cost = 150000, 
      emd = 7500 
  WHERE id = '9816988'
`).run();

// 2. Clear old pending approvals and insert test cases
db.prepare('DELETE FROM tender_approval_requests').run();

const insertApproval = db.prepare(`
  INSERT INTO tender_approval_requests (
    tender_id, stage, requested_by, assigned_to, status, working_path,
    emd_amount, transfer_mode, transfer_ref_no, receipt_file_url,
    loss_reason_executive, created_at, updated_at
  ) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
`);

// EMD Payment Approval
insertApproval.run('9849390', 'PAYMENT_APPROVAL', 'executive_1', 'misteam', '/documents/9849390/payment', 25000, 'RTGS', 'RTGS-IND-9849390', '/documents/receipt-sample.pdf', null);

// Document Verification
insertApproval.run('9712000', 'DOC_VERIFICATION', 'executive', 'misteam', '/documents/9712000/Compiled_Bid_Package', null, null, null, null, null);

// Submission Confirmation
insertApproval.run('8715358', 'SUBMISSION_PENDING', 'executive_1', 'misteam', '/documents/8715358/Final_Submitted_Zip', null, null, null, null, null);

// Win/Loss Outcome Verification
insertApproval.run('9808508', 'WIN_LOSS_PENDING', 'executive', 'misteam', null, null, null, null, null, 'Competitor ABC quoted Rs. 1,85,000 (L1), Marken quote was Rs. 2,10,000 (L2).');

// Spec Clearance Review
insertApproval.run('9816988', 'SPEC_CLEARANCE', 'executive_1', 'misteam', '/documents/9816988/Technical_Specifications_Rev2.pdf', null, null, null, null, null);

console.log('Successfully seeded 5 pending approval requests.');

const counts = db.prepare('SELECT stage, COUNT(*) as c FROM tender_approval_requests GROUP BY stage').all();
console.log('Current approval stage counts:', counts);
