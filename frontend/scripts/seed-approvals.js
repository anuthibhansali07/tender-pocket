const Database = require('better-sqlite3');
const path = require('path');

const db = new Database(path.join(__dirname, '..', 'tenders.db'));

// Fix existing approvals: reassign to 'mis team' queue so Admin/MIS Team can see them
console.log('Fixing existing approval assignments...');

// SPEC_CLEARANCE goes to spec team queue, rest go to MIS Team queue
db.prepare("UPDATE tender_approval_requests SET assigned_to = 'specification team' WHERE status = 'PENDING' AND stage = 'SPEC_CLEARANCE'").run();
db.prepare("UPDATE tender_approval_requests SET assigned_to = 'mis team' WHERE status = 'PENDING' AND stage != 'SPEC_CLEARANCE'").run();

console.log('Fixed existing approvals.');

// Get tender IDs for seeding
const tenders = db.prepare('SELECT id, title, ref_no, authority, estimated_cost, emd, due_date FROM tenders ORDER BY scraped_at DESC LIMIT 15').all();
console.log('Available tenders:', tenders.length);

const now = new Date().toISOString();
const d1 = new Date(Date.now() - 1 * 86400000).toISOString();
const d2 = new Date(Date.now() - 2 * 86400000).toISOString();
const d3 = new Date(Date.now() - 3 * 86400000).toISOString();

const insertApproval = db.prepare(`
  INSERT INTO tender_approval_requests 
  (tender_id, stage, requested_by, assigned_to, status, working_path, emd_amount, transfer_mode, transfer_ref_no, receipt_file_url, loss_reason_executive, tpc_purchase_price, mis_final_price, created_at, updated_at)
  VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
`);

const seeds = [
  // PAYMENT_APPROVAL: EMD payment with full details
  tenders[0] && [
    tenders[0].id, 'PAYMENT_APPROVAL', 'misteam', 'mis team',
    '/Shared/Tenders/2026/' + tenders[0].id,
    25000, 'Online / NEFT', 'NEFT-MKN-' + tenders[0].id + '-SEP26',
    '/documents/' + tenders[0].id + '/neft_receipt.pdf',
    null, null, null, now, now
  ],
  // DOC_VERIFICATION: Document review pending
  tenders[1] && [
    tenders[1].id, 'DOC_VERIFICATION', 'misteam', 'mis team',
    '/Shared/Tenders/2026/' + tenders[1].id,
    null, null, null, null, null, null, null, d1, d1
  ],
  // SPEC_CLEARANCE: Spec review for Specification Team
  tenders[2] && [
    tenders[2].id, 'SPEC_CLEARANCE', 'misteam', 'specification team',
    '/Shared/Tenders/2026/' + tenders[2].id,
    null, null, null, null, null, null, null, d2, d2
  ],
  // MIS_PRICING: Final pricing approval
  tenders[3] && [
    tenders[3].id, 'MIS_PRICING', 'misteam', 'mis team',
    '/Shared/Tenders/2026/' + tenders[3].id,
    null, null, null, null, null, null, 345000, d3, d3
  ],
  // WIN_LOSS_PENDING: Loss outcome with reason
  tenders[4] && [
    tenders[4].id, 'WIN_LOSS_PENDING', 'misteam', 'mis team',
    null, null, null, null, null,
    'Competitor quoted 8.5% lower on unit price. L1 bid from XYZ Corp Pvt Ltd.',
    null, null, d1, d1
  ],
  // SUBMISSION_PENDING: Submission confirmation
  tenders[5] && [
    tenders[5].id, 'SUBMISSION_PENDING', 'misteam', 'mis team',
    '/Shared/Tenders/2026/' + tenders[5].id,
    null, null, null, null, null, null, null, now, now
  ],
  // PAYMENT_APPROVAL: Demand Draft payment
  tenders[6] && [
    tenders[6].id, 'PAYMENT_APPROVAL', 'misteam', 'mis team',
    '/Shared/Tenders/2026/' + tenders[6].id,
    48500, 'Demand Draft', 'DD-MKN-' + tenders[6].id + '-A002',
    '/documents/' + tenders[6].id + '/dd_scan.pdf',
    null, null, null, d2, d2
  ],
  // TPC_PRICING: TPC purchase price recorded
  tenders[7] && [
    tenders[7].id, 'TPC_PRICING', 'misteam', 'mis team',
    '/Shared/Tenders/2026/' + tenders[7].id,
    null, null, null, null, null, 285000, null, d3, d3
  ],
];

let inserted = 0;
for (const seed of seeds) {
  if (!seed) continue;
  try {
    insertApproval.run(...seed);
    inserted++;
    console.log('Inserted approval for tender:', seed[0], 'stage:', seed[1]);
  } catch (e) {
    console.error('Failed to insert for tender', seed[0], ':', e.message);
  }
}

const total = db.prepare("SELECT COUNT(*) as c FROM tender_approval_requests WHERE status='PENDING'").get();
console.log('\nTotal PENDING approvals now:', total.c);
console.log('Newly inserted:', inserted);

db.close();
console.log('Done!');
