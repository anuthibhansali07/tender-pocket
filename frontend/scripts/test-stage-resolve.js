const Database = require('better-sqlite3');
const path = require('path');
const { resolveTenderStageDetails } = require('../src/lib/tenderStatus');

const db = new Database(path.join(__dirname, '../tenders.db'));
const tenders = db.prepare('SELECT * FROM tenders').all();

console.log('Resolving stages for all tenders...');
const stages = {};
const docPrepTenders = [];
const emdTenders = [];

tenders.forEach(t => {
  const stage = resolveTenderStageDetails(t, 'Admin');
  stages[stage.stageKey] = (stages[stage.stageKey] || 0) + 1;
  if (stage.stageKey === 'BID_DOCS_PREP') {
    docPrepTenders.push({ id: t.id, ref_no: t.ref_no, current_stage: t.current_stage, payment_status: t.payment_status, emd: t.emd, emd_raw: t.emd_raw, emd_beneficiary_name: t.emd_beneficiary_name, working_path: t.working_path, spec_verification_status: t.spec_verification_status, downloaded_docs: t.downloaded_docs ? t.downloaded_docs.slice(0, 100) : null });
  }
  if (stage.stageNumber === 4 || stage.stageKey.includes('EMD')) {
    emdTenders.push({ id: t.id, ref_no: t.ref_no, current_stage: t.current_stage });
  }
});

console.log('Stages summary:', stages);
console.log('Total doc prep tenders:', docPrepTenders.length);
console.log('Doc prep tenders:', JSON.stringify(docPrepTenders, null, 2));
console.log('EMD tenders:', emdTenders);
