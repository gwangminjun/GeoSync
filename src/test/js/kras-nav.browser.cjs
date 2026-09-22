// Run after KrasIntegrationViewTest; PLAYWRIGHT_MODULE points to an installed playwright package.
// 목록·상세 사이드바(검색/상태필터/팝업)와 반영 전 비교 패널을 검증한다 — 기존 카드 HTML/JS는 건드리지 않았는지도
// 간접 확인한다(카드 버튼이 여전히 동작하는지는 kras-history.browser.cjs가 이미 검증한다).
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const item = { item_id: 44, dataset_code: 'land_info', status: 'SUCCESS', rows_valid: 1,
      rows_rejected: 0, window_start: '2026-09-22', window_end_exclusive: '2026-09-23' };
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      const send = data => route.fulfill({ json: data });
      if (url.pathname === '/kras-db') {
        return route.fulfill({ contentType: 'text/html', body: fs.readFileSync('build/kras-ui/kras-db.html', 'utf8') });
      }
      if (url.pathname === '/js/kras-history.js') return route.fulfill({ contentType: 'text/javascript', body: fs.readFileSync('src/main/resources/static/js/kras-history.js', 'utf8') });
      if (url.pathname === '/kras-db/history') return send({ items: [item], hasOperationLog: false, operations: [] });
      if (url.pathname === '/kras-db/items/44') return send({ item, promotable: true, reasons: [],
        promotePath: '/kras-db/promote/land-info',
        preview: [{ table: 'kras.stage_parcel', truncated: false, rows: [{ pnu: '1283025021100010000' }] }],
        comparison: [
          { businessTable: 'kras.parcel', pattern: 'NATURAL_KEY_UPSERT', stageCount: 1, note: '기존 행 UPDATE 예상(자연키 일치)' },
          { businessTable: 'kras.land_owner', pattern: 'NATURAL_KEY_UPSERT', stageCount: 1, note: '신규 행 INSERT 예상(자연키 불일치)' },
        ] });
      return route.fulfill({ status: 200, body: '' });
    });
    await page.goto('http://geosync.test/kras-db');
    await page.locator('#dataset-nav-search').waitFor();

    // 초기 상태: use-zone 항목과 그룹이 보인다.
    const navItem = page.locator('.dataset-nav-item[data-slug="use-zone"]');
    await assert.equal(await navItem.isVisible(), true, '초기 목록에 use-zone이 보여야 함');

    // 검색어가 안 맞으면 항목/그룹이 숨고 빈 메시지가 뜬다.
    await page.locator('#dataset-nav-search').fill('없는연계코드xyz');
    await page.waitForFunction(() => document.getElementById('dataset-nav-empty').hidden === false);
    assert.equal(await navItem.isVisible(), false, '검색어 불일치 시 숨어야 함');

    // 검색어를 지우고 상태 필터를 VERIFIED로 걸면 UNVERIFIED 항목은 숨는다.
    await page.locator('#dataset-nav-search').fill('');
    await page.locator('#dataset-nav-status').selectOption('VERIFIED');
    assert.equal(await navItem.isVisible(), false, 'UNVERIFIED 항목은 VERIFIED 필터에서 숨어야 함');
    await page.locator('#dataset-nav-status').selectOption('');
    assert.equal(await navItem.isVisible(), true, '필터 해제 후 다시 보여야 함');

    // 목록 클릭 → 팝업에 해당 카드가 뜬다(카드 자체를 옮긴 것이므로 버튼 id/onclick도 그대로 살아있다).
    await navItem.locator('a').click();
    await page.locator('#dataset-modal-backdrop:not([hidden])').waitFor();
    assert.equal(await page.locator('#dataset-modal-body #card-use-zone').isVisible(), true,
      '팝업 안에 실제 카드 노드가 들어가야 함');
    assert.equal(await page.locator('#card-use-zone').count(), 1, '카드는 복제가 아니라 이동이어야 함(중복 없음)');
    await page.locator('#dataset-modal-close').click();
    await page.locator('#dataset-modal-backdrop[hidden]').waitFor({ state: 'attached' });
    assert.equal(await page.locator('#dataset-modal-body #card-use-zone').count(), 0,
      '닫으면 카드가 팝업 밖으로(원래 자리로) 돌아와야 함');
    assert.equal(await page.locator('#card-use-zone').isVisible(), false,
      '닫으면 카드는 다시 기본 숨김(kras-hide) 상태여야 함 — 스크롤 화면에 노출되면 안 됨');

    // 백드롭 클릭으로도 닫힌다.
    await navItem.locator('a').click();
    await page.locator('#dataset-modal-backdrop:not([hidden])').waitFor();
    await page.locator('#dataset-modal-backdrop').click({ position: { x: 5, y: 5 } });
    await page.locator('#dataset-modal-backdrop[hidden]').waitFor({ state: 'attached' });

    // companion 카드가 있는 데이터셋(land_info: 계약상태 카드 + PNU 테스트 카드)은 둘 다 같이 옮겨져야 한다.
    await page.evaluate(() => krasOpenDatasetModal('land-info', 'land_info'));
    await page.locator('#dataset-modal-backdrop:not([hidden])').waitFor();
    assert.equal(await page.locator('#dataset-modal-body #card-land-info').count(), 1, '주 카드가 팝업에 있어야 함');
    assert.equal(await page.locator('#dataset-modal-body [data-companion-of="land-info"]').count(), 1,
      'companion 카드도 같이 옮겨져야 함');
    await page.locator('#dataset-modal-close').click();
    await page.locator('#dataset-modal-backdrop[hidden]').waitFor({ state: 'attached' });
    assert.equal(await page.locator('#dataset-modal-body [data-companion-of="land-info"]').count(), 0,
      '닫으면 companion 카드도 팝업 밖으로 돌아와야 함');
    assert.equal(await page.locator('[data-companion-of="land-info"]').isVisible(), false,
      '닫으면 companion 카드도 다시 기본 숨김 상태여야 함');

    // 반영 전 비교 패널 — 수집 건 선택 시 서버가 준 비교 정보를 그대로 보여준다.
    await page.locator('#history-items button').click();
    await page.locator('#history-detail:not([hidden])').waitFor();
    await page.locator('#history-comparison:not([hidden])').waitFor();
    const comparisonText = await page.locator('#history-comparison').textContent();
    assert.match(comparisonText, /kras\.parcel.*UPDATE 예상/);
    assert.match(comparisonText, /kras\.land_owner.*INSERT 예상/);

    assert.deepEqual(errors, [], '브라우저 JavaScript 오류 없음');
    console.log('PASS: dataset nav search/status filter, popup open/close/backdrop, comparison panel.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
