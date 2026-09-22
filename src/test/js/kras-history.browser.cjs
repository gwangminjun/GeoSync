// Run after KrasIntegrationViewTest; PLAYWRIGHT_MODULE points to an installed playwright package.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } });
    const errors = [];
    const promotions = [];
    let txtCalls = 0;
    let swept = false;
    page.on('pageerror', error => errors.push(error.message));
    page.on('dialog', dialog => dialog.accept());
    const item = { item_id: 44, dataset_code: 'land_info', status: 'SUCCESS', rows_valid: 1,
      rows_rejected: 0, window_start: '2026-09-22', window_end_exclusive: '2026-09-23' };
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      const send = data => route.fulfill({ json: data });
      if (url.pathname === '/kras-db') {
        let html = fs.readFileSync('build/kras-ui/kras-db.html', 'utf8');
        if (swept) html = html.replace(/(현재 release<\/div>\s*<div class="stat-value">)[^<]*/, (_, prefix) => prefix + '9 (SEALED)');
        return route.fulfill({ contentType: 'text/html', body: html });
      }
      if (url.pathname === '/js/kras-history.js') return route.fulfill({ contentType: 'text/javascript', body: fs.readFileSync('src/main/resources/static/js/kras-history.js', 'utf8') });
      if (url.pathname === '/kras-db/history') return send({ items: [item], hasOperationLog: true, operations: [
        { dataset_code: 'usezone_file', action: 'SWEEP', status: 'PARTIAL', started_at: '2026-09-22 14:20', ended_at: '2026-09-22 14:21',
          message: '2개 레이어 중 1개 실패', details: JSON.stringify({ layers: [{ layerCode: 'UQ1', success: false, message: '다운로드 실패' }] }) }
      ] });
      if (url.pathname === '/kras-db/items/44') return send({ item, promotable: true, reasons: [],
        promotePath: '/kras-db/promote/land-info', preview: [{ table: 'kras.stage_land_register', truncated: false, rows: [{ pnu: '4687025021100010000', jimok: '01' }] }] });
      if (url.pathname === '/kras-db/promote/land-info') {
        promotions.push(url.searchParams.get('itemId'));
        return send({ success: true, outcome: 'SUCCESS', message: '업무 테이블 반영 완료', itemId: 44 });
      }
      if (url.pathname === '/kras-db/usezone/collect-catalog') return send({ success: true, outcome: 'SUCCESS', itemId: 7, message: '카탈로그 수집 완료' });
      if (url.pathname === '/kras-db/usezone/sweep') {
        swept = true;
        return send({ success: true, outcome: 'PARTIAL', releaseId: 9, message: '레이어 2개 중 1개 실패',
          layers: [{ layerCode: 'UQ1', success: false, message: '다운로드 실패' }, { layerCode: 'UQ2', success: true, message: '완료' }] });
      }
      if (url.pathname === '/kras-db/ingest/land-basic-file') return send(++txtCalls === 1
        ? { success: true, outcome: 'SUCCESS', itemId: 51, promotable: true, message: '수집 완료' }
        : { success: false, outcome: 'FAILED', message: '재수집 실패' });
      return route.fulfill({ status: 200, body: '' });
    });
    await page.goto('http://geosync.test/kras-db');
    await page.locator('#history-items button').waitFor();
    // 데이터셋 카드는 이제 기본적으로 숨겨져 있고 팝업을 열어야 나타난다(kras-hide).
    await page.evaluate(() => krasOpenDatasetModal('land-basic-file', 'land_basic_file'));
    assert.equal(await page.locator('#btn-land-basic-file-promote').isDisabled(), true, '수집 전 반영 버튼은 비활성');
    await page.evaluate(() => krasDatasetModal.close());

    await page.locator('#history-items button').click();
    await page.locator('#history-detail:not([hidden])').waitFor();
    assert.equal(await page.locator('#history-promote').isEnabled(), true);
    await page.reload();
    await page.locator('#history-items button').click();
    await page.locator('#history-detail:not([hidden])').waitFor();
    await page.locator('#history-promote').click();
    await page.waitForFunction(() => document.getElementById('history-message').textContent.includes('반영 완료'));
    assert.deepEqual(promotions, ['44'], '새로고침 후 DB 이력에서 선택한 item 반영');

    await page.evaluate(() => krasOpenDatasetModal('usezone-file', 'usezone_file'));
    await page.locator('#btn-usezone-catalog').waitFor({ state: 'visible' });
    await page.locator('#btn-usezone-catalog').click();
    await page.waitForFunction(() => !document.getElementById('btn-usezone-sweep').disabled);
    await page.locator('#btn-usezone-sweep').click();
    await page.waitForFunction(() => document.getElementById('usezone-result-js').classList.contains('warn'));
    assert.match(await page.locator('#usezone-layers-js').textContent(), /다운로드 실패/);
    await page.waitForFunction(() => document.getElementById('usezone-counts').textContent.includes('9 (SEALED)'));
    assert.equal(await page.locator('#btn-usezone-publish').isDisabled(), true);
    await page.evaluate(() => krasDatasetModal.close());

    await page.evaluate(() => krasOpenDatasetModal('land-basic-file', 'land_basic_file'));
    await page.locator('#btn-land-basic-file-ingest').waitFor({ state: 'visible' });
    await page.locator('#btn-land-basic-file-ingest').click();
    await page.waitForFunction(() => !document.getElementById('btn-land-basic-file-promote').disabled);
    await page.locator('#btn-land-basic-file-ingest').click();
    await page.waitForFunction(() => document.getElementById('land-basic-file-result-js').textContent.includes('재수집 실패'));
    assert.equal(await page.locator('#btn-land-basic-file-promote').isDisabled(), true, '재수집 실패 후 이전 item 반영 차단');
    await page.evaluate(() => krasDatasetModal.close());

    await page.locator('#integration-history details').nth(1).evaluate(el => { el.open = true; });
    await page.locator('#integration-history').scrollIntoViewIfNeeded();
    await page.screenshot({ path: 'build/kras-ui/kras-history-desktop.png' });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('#integration-history').scrollIntoViewIfNeeded();
    await page.screenshot({ path: 'build/kras-ui/kras-history-mobile.png' });
    assert.deepEqual(errors, [], '브라우저 JavaScript 오류 없음');
    console.log('PASS: reload/resume, partial sweep, stale TXT selection, rendered controls; screenshots saved.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
