const Database = require('better-sqlite3');
const path = require('path');

const db = new Database(path.join(__dirname, '../tenders.db'));

const tenders = db.prepare('SELECT id, ref_no, title, status, current_stage, payment_status, emd, emd_raw, emd_applicable, emd_beneficiary_name, emd_amount_actual, emd_payment_mode, emd_payment_ref, emd_payment_date, working_path, spec_verification_status, downloaded_docs, mis_executive FROM tenders').all();

const active = tenders.filter(t => t.status === 'Participating' || (t.status === 'New' && t.current_stage) || t.payment_status !== 'None' || (t.current_stage && t.current_stage !== 'NEW_UNREVIEWED'));

console.log('Active tenders count:', active.length);
active.forEach(t => {
  console.log('----------------------------------------------------');
  console.log(JSON.stringify(t, null, 2));
});
