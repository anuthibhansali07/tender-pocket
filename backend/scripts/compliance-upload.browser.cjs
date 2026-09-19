const { chromium } = require("../../target/compliance-browser/node_modules/playwright");
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");

(async () => {
  const base = "http://localhost:8080";
  const id = "9732250";
  const detail = await (await fetch(`${base}/api/tenders/${id}`)).json();
  assert(detail.tender, "Read-only tender fixture must exist");
  const tender = {...detail.tender, id, status: "Missed Deadline",
    has_tech_spec: false, spec_verification_status: "Not Started"};
  const script = fs.readFileSync(path.resolve(__dirname, "../src/main/resources/static/compliance-progress.js"), "utf8");
  const browser = await chromium.launch({channel: "msedge", headless: true});
  try {
    for (const width of [1440, 390]) {
      const page = await browser.newPage({viewport: {width, height: 1000}});
      const mutations = [];
      const errors = [];
      let uploaded = 0;
      let posting = false;
      const notes = Array.from({length: 45}, (_, i) => `Clause 3.${i + 1}: native PDF reading could not be matched exactly to extracted text. Review required: verify wording and numerical values. [PDF p. ${50 + Math.floor(i / 5)} (Printed p. ${213 + Math.floor(i / 5)})]`);
      const configurationWarning = "TECHSPEC_USD_TO_INR_RATE is missing; estimated INR cost is unavailable.";
      const metrics = {model: "gpt-5-nano", tokens: {total: 10000},
        warnings: [...notes, notes[0], configurationWarning]};
      page.on("pageerror", error => errors.push(error.message));
      await page.addInitScript(() => {
        localStorage.setItem("currentUser", JSON.stringify({username: "admin", role: "Admin"}));
      });
      await page.route("**/compliance-progress.js*", route =>
        route.fulfill({contentType: "text/javascript", body: script}));
      await page.route("**/api/**", async route => {
        const request = route.request();
        const url = new URL(request.url());
        if (request.method() !== "GET") {
          mutations.push(url.pathname);
          if (!url.pathname.endsWith("/upload-tech-spec")) return route.fulfill({json: {success: true}});
          assert.equal(request.headers()["x-user-role"], "Admin");
          assert.match(request.postDataBuffer().toString(), /name="file"/);
          uploaded++;
          posting = true;
          if (uploaded === 2) await new Promise(resolve => setTimeout(resolve, 1800));
          posting = false;
          return route.fulfill({json: uploaded !== 2
            ? {success: true, generated: false, products: [],
                message: "No products found with technical specifications."}
            : {success: true, generated: true, message: "Sheets ready", metrics,
                products: [{productName: "Battery", scheduleNumber: "1", clauseCount: 1,
                  pdfDownloadUrl: "/documents/test/battery.pdf", docxDownloadUrl: "/documents/test/battery.docx"}]}});
        }
        if (url.pathname.endsWith("tech-spec-progress")) return route.fulfill({json: posting && uploaded === 2
          ? {status: "AI", jobId: "review-test", message: `Batch 1/2: Review warning: ${notes[0]}`, metrics,
              events: [{stage: "AI", message: `Batch 1/2: Review warning: ${notes[0]}`}]}
          : {status: "NOT_STARTED"}});
        if (url.pathname === `/api/tenders/${id}`) return route.fulfill({json: {success: true, tender}});
        if (url.pathname === "/api/tenders") return route.fulfill({json: {
          success: true, tenders: [tender], total: 1, totalCount: 1, page: 1, totalPages: 1,
          filters: {locations: [], sectors: []}}});
        return route.fulfill({json: {success: true, users: [], executives: [], logs: [],
          comments: [], history: [], tenders: [], stats: {}, analytics: {}}});
      });
      await page.goto(`${base}/tenders/${id}`);
      const form = page.locator("#compliance-admin-upload");
      await form.waitFor({timeout: 30000});
      assert.equal(await form.count(), 1);
      for (let run = 0; run < 2; run++) {
        await form.locator('input[type="file"]').setInputFiles({
          name: "specification.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-test")});
        await form.getByRole("button", {name: "Generate compliance sheets"}).click();
        await page.waitForFunction(expected =>
          document.querySelector(".cp-message")?.textContent === expected,
        run === 0 ? "No products found with technical specifications." : "Sheets ready");
        assert.equal(await page.locator(".cp-links a").count(), run === 0 ? 0 : 2);
        await form.getByRole("button", {name: "Generate compliance sheets"}).waitFor();
      }
      assert.equal(uploaded, 2);
      const reviewButton = page.locator(".cp-review-button");
      assert.equal(await page.locator(".cp-review-count").innerText(), "45");
      assert.equal(await page.locator(".cp-metric-warnings").innerText(), configurationWarning);
      assert(!/native PDF reading/.test(await page.locator(".cp-events").innerText()));
      await reviewButton.click();
      const dialog = page.getByRole("dialog", {name: "Review notes"});
      await dialog.waitFor();
      assert.equal(await dialog.locator(".cp-review-item").count(), 45);
      assert.equal(await dialog.locator(".cp-review-group").count(), 9);
      assert.match(await dialog.locator(".cp-review-group h3").first().innerText(), /PDF p\. 50/);
      const search = dialog.getByRole("searchbox", {name: "Search review notes"});
      await search.fill("3.10:");
      assert.equal(await dialog.locator(".cp-review-item").count(), 1);
      await search.fill("no-such-clause");
      assert.equal(await dialog.locator(".cp-review-empty").innerText(), "No matching review notes.");
      await search.fill("");
      const downloadEvent = page.waitForEvent("download");
      await dialog.getByRole("button", {name: "Download notes"}).click();
      const download = await downloadEvent;
      const downloaded = fs.readFileSync(await download.path(), "utf8");
      assert(downloaded.includes(notes[0]) && downloaded.includes(notes[44]));
      const dialogBounds = await dialog.boundingBox();
      assert(dialogBounds.x >= 0 && dialogBounds.x + dialogBounds.width <= width + 1);
      const out = path.resolve(__dirname, "../target/compliance-upload-browser");
      fs.mkdirSync(out, {recursive: true});
      await dialog.screenshot({path: path.join(out, `review-notes-${width}.png`)});
      await page.keyboard.press("Escape");
      assert.equal(await dialog.count(), 0);
      assert.equal(await reviewButton.evaluate(node => node === document.activeElement), true);
      await reviewButton.click();
      assert.equal(await page.locator(".cp-review-item").count(), 45);
      await page.getByRole("button", {name: "Close review notes", exact: true}).click();
      await form.locator('input[type="file"]').setInputFiles({
        name: "another.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-test")});
      await form.getByRole("button", {name: "Generate compliance sheets"}).click();
      await page.waitForFunction(() => document.querySelector(".cp-message")?.textContent ===
        "No products found with technical specifications.");
      assert.equal(await page.locator(".cp-review-actions").isVisible(), false);
      assert(mutations.every(url => url.endsWith("/upload-tech-spec")),
        `Unexpected unrelated workflow mutation: ${mutations}`);
      const bounds = await form.boundingBox();
      assert(bounds && bounds.x >= 0 && bounds.x + bounds.width <= width + 1);
      assert.deepEqual(errors, []);
      await page.screenshot({path: path.join(out, `admin-${width}.png`), fullPage: true});
      console.log(`PASS Admin ${width}px: upload, 45 grouped review notes, search, export, keyboard focus, new-job reset, no bid-pack calls`);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
