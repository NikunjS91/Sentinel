/**
 * Sentinel UI screenshot capture script.
 *
 * Prerequisites:
 *   npm install && npx playwright install chromium
 *
 * Usage:
 *   node capture.mjs          # headless
 *   HEADED=1 node capture.mjs # watch the browser
 *
 * Requires the full stack running:
 *   docker compose up -d
 *   cd orchestrator && mvn spring-boot:run    # :8080
 *   cd agents && uvicorn app.main:app --port 8001
 *   cd ui && npm run dev                      # :5173
 *
 * Seed incidents (Step 2 of UI-WALKTHROUGH-AND-README.md) before running.
 */

import { chromium } from 'playwright';
import { mkdirSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUTPUT_DIR = join(__dirname, '..', '..', 'docs', 'screenshots');
mkdirSync(OUTPUT_DIR, { recursive: true });

const UI = 'http://localhost:5173';
const VIEWPORT = { width: 1440, height: 900 };

const browser = await chromium.launch({ headless: !process.env.HEADED });
const ctx = await browser.newContext({ viewport: VIEWPORT });
const page = await ctx.newPage();

async function goto() {
  // Use 'load' not 'networkidle' — SSE keeps connections open forever.
  await page.goto(UI, { waitUntil: 'load', timeout: 15000 });
  // Wait for SSE to connect and populate incidents (indicator turns green / "live").
  try {
    await page.waitForSelector('span.conn-indicator', { timeout: 8000 });
  } catch {}
  await page.waitForTimeout(4000);
}

async function snap(name) {
  const path = join(OUTPUT_DIR, `${name}.png`);
  await page.screenshot({ path, fullPage: false });
  console.log(`  ✓ ${name}.png`);
}

async function snapElement(name, locator) {
  const path = join(OUTPUT_DIR, `${name}.png`);
  await locator.screenshot({ path });
  console.log(`  ✓ ${name}.png`);
}

console.log('Capturing Sentinel UI screenshots...\n');

// ── 1. Hero — populated dashboard, no filters active ────────────────────────
await goto();
await snap('01-dashboard-hero');

// ── 2. Expanded incident — click first PARTIAL/RESOLVED card for rich detail ──
await goto();
const allIncidents = page.locator('article.incident');
const incCount = await allIncidents.count();
let expanded = false;
// Prefer a terminal incident (PARTIAL/RESOLVED) for richer detail view.
for (const keyword of ['PARTIAL', 'RESOLVED', 'AGGREGATING']) {
  if (expanded) break;
  for (let i = 0; i < incCount && !expanded; i++) {
    const card = allIncidents.nth(i);
    const text = await card.textContent();
    if (text && text.includes(keyword)) {
      await card.click();
      await page.waitForTimeout(1000);
      expanded = true;
    }
  }
}
if (!expanded) {
  await allIncidents.first().click();
  await page.waitForTimeout(1000);
}
await snap('02-incident-expanded');

// ── 3. Filter: undecided SRE queue ───────────────────────────────────────────
await goto();
// Decision filter is the 3rd <select> inside .filter-bar
await page.locator('div.filter-bar select').nth(2).selectOption('undecided');
await page.waitForTimeout(1200);
await snap('03-filter-undecided');

// ── 4. Filter: terminal states only ──────────────────────────────────────────
await goto();
// State filter is the 1st <select> inside .filter-bar
await page.locator('div.filter-bar select').nth(0).selectOption('RESOLVED');
await page.waitForTimeout(1200);
await snap('04-filter-resolved');

// ── 5. Edit form — expand an incident and open the edit UI ───────────────────
await goto();
// Find the first RESOLVED incident that has no human decision (undecided).
// Fallback: just click the first incident and try.
const incidents = page.locator('article.incident');
const count = await incidents.count();
let opened = false;
for (let i = 0; i < count && !opened; i++) {
  await incidents.nth(i).click();
  await page.waitForTimeout(500);
  const editBtn = page.locator('div.actions button', { hasText: 'Edit' });
  if (await editBtn.count() > 0) {
    await editBtn.first().click();
    await page.waitForTimeout(500);
    await snap('05-edit-form');
    opened = true;
  } else {
    // Collapse and try the next one.
    await incidents.nth(i).click();
    await page.waitForTimeout(300);
  }
}
if (!opened) {
  console.log('  ⚠ 05-edit-form.png skipped — no undecided terminal incident found');
}

// ── 6. Connection indicator — header crop ────────────────────────────────────
await goto();
const header = page.locator('header');
if (await header.count() > 0) {
  await snapElement('06-connection-live', header);
} else {
  await snap('06-connection-live');
}

await browser.close();
console.log(`\nDone — screenshots saved to docs/screenshots/`);
