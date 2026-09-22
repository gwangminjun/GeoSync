'use strict';

function krasResultClass(data) {
  if (data.outcome === 'PARTIAL' || data.outcome === 'WARNING' || data.auditWarning) return 'warn';
  return data.success && data.outcome !== 'FAILED' ? 'ok' : 'err';
}

function krasResultMessage(data) {
  return (data.message || data.error || JSON.stringify(data)) +
    (data.auditWarning ? '\n' + data.auditWarning : '');
}

async function krasRequest(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) throw new Error('요청 실패 (HTTP ' + response.status + '). 서버 로그와 연결 상태를 확인하세요.');
  return response;
}

document.addEventListener('DOMContentLoaded', () => {
  const byId = id => document.getElementById(id);
  const filter = byId('history-dataset');
  if (!filter) return;
  const message = byId('history-message');
  const promoteButton = byId('history-promote');
  let selected = null;
  let selectionVersion = 0;
  let historyVersion = 0;
  let promoting = false;
  const statusLabels = { SUCCESS: '완료', FAILED: '실패', PARTIAL: '일부 실패', WARNING: '확인 필요',
    RUNNING: '실행 중', PLANNED: '검증 대기', COLLECTING: '수집 중', READY: '준비 완료', BLOCKED: '차단', INTERRUPTED: '중단' };
  const actionLabels = { INGEST: '수집', PROMOTE: '업무 반영', SWEEP: '레이어 수집', PUBLISH: '릴리즈 발행', PUBLIC: '지도 반영' };
  const datasetCodes = new Set([...document.querySelectorAll('input[name="datasetCode"]')].map(el => el.value));
  [...datasetCodes].sort().forEach(code => {
    const option = document.createElement('option');
    option.value = code;
    option.textContent = code;
    filter.appendChild(option);
  });

  function showMessage(text, style = '') {
    message.textContent = text;
    message.className = 'result-box ' + style;
  }
  function cell(row, text) {
    const td = document.createElement('td');
    td.textContent = text == null ? '—' : String(text);
    row.appendChild(td);
    return td;
  }
  function emptyRow(body, columns, text) {
    const tr = document.createElement('tr');
    cell(tr, text).colSpan = columns;
    body.appendChild(tr);
  }
  function resetSelection() {
    selectionVersion++;
    selected = null;
    promoteButton.disabled = true;
    byId('history-detail').hidden = true;
  }

  async function selectItem(itemId) {
    if (promoting) return;
    resetSelection();
    const version = selectionVersion;
    showMessage('수집 건을 확인하는 중입니다.');
    try {
      const data = await (await krasRequest('/kras-db/items/' + encodeURIComponent(itemId))).json();
      if (version !== selectionVersion) return;
      selected = data;
      byId('history-detail').hidden = false;
      byId('history-detail-title').textContent = data.item.dataset_code + ' · 수집 ID ' + data.item.item_id;
      byId('history-detail-reasons').textContent = data.reasons.length ? data.reasons.join('\n') : '수집 검증 완료. 내용을 확인한 뒤 반영하세요.';
      byId('history-detail-reasons').className = 'result-box ' + (data.promotable ? 'ok' : 'warn');
      byId('history-preview').textContent = JSON.stringify(data.preview, null, 2);
      promoteButton.disabled = !data.promotable;
      showMessage('선택한 수집 건의 상세를 아래에서 확인하세요.');
    } catch (error) {
      if (version === selectionVersion) showMessage(error.message, 'err');
    }
  }

  async function loadHistory() {
    const version = ++historyVersion;
    showMessage('이력을 불러오는 중입니다.');
    try {
      const data = await (await krasRequest('/kras-db/history?dataset=' + encodeURIComponent(filter.value))).json();
      if (version !== historyVersion) return;
      const items = byId('history-items');
      items.replaceChildren();
      data.items.forEach(item => {
        const tr = document.createElement('tr');
        cell(tr, item.item_id);
        cell(tr, item.dataset_code + (item.scope_key ? '\n' + item.scope_key : ''));
        cell(tr, item.status === 'SUCCESS' ? '수집 검증 완료' : (statusLabels[item.status] || item.status));
        cell(tr, item.rows_valid + ' / ' + item.rows_rejected);
        cell(tr, item.window_start + ' ~ ' + item.window_end_exclusive + ' (종료일 미포함)' +
          (item.started_at ? '\n' + item.started_at : ''));
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'btn btn-secondary';
        button.textContent = '미리보기 / 이어하기';
        button.addEventListener('click', () => selectItem(item.item_id));
        cell(tr, '').appendChild(button);
        items.appendChild(tr);
      });
      if (!data.items.length) emptyRow(items, 6, '수집 이력이 없습니다.');
      const operations = byId('history-operations');
      operations.replaceChildren();
      data.operations.forEach(operation => {
        const tr = document.createElement('tr');
        cell(tr, operation.dataset_code + ' / ' + (actionLabels[operation.action] || operation.action) +
          (operation.request_summary ? '\n' + operation.request_summary : ''));
        cell(tr, statusLabels[operation.status] || operation.status).className = 'history-status';
        cell(tr, operation.started_at + '\n' + (operation.ended_at || '종료 기록 없음'));
        cell(tr, operation.item_id);
        const detail = cell(tr, operation.message);
        try {
          const layers = JSON.parse(operation.details || '{}').layers;
          if (layers) {
            const block = document.createElement('details');
            const summary = document.createElement('summary');
            summary.textContent = '레이어별 결과';
            const pre = document.createElement('pre');
            pre.textContent = layers.map(layer => layer.layerCode + ': ' + (layer.success ? '완료' : '실패') + ' · ' + layer.message).join('\n');
            block.append(summary, pre);
            detail.appendChild(block);
          }
        } catch (_) { /* 이전 형식의 상세 기록은 메시지만 표시한다. */ }
        operations.appendChild(tr);
      });
      if (!data.operations.length) emptyRow(operations, 5, '실행 기록이 없습니다. 이 기능 도입 이후 실행부터 기록합니다.');
      showMessage('이력을 갱신했습니다. 수집 완료와 업무 반영 완료는 별도 상태입니다.');
    } catch (error) {
      if (version === historyVersion) showMessage(error.message, 'err');
    }
  }

  byId('history-refresh').addEventListener('click', loadHistory);
  filter.addEventListener('change', () => { resetSelection(); loadHistory(); });
  byId('history-check').addEventListener('click', async () => {
    if (!filter.value) { showMessage('조건을 확인할 연계를 선택하세요.', 'warn'); return; }
    try {
      const data = await (await krasRequest('/kras-db/readiness?dataset=' + encodeURIComponent(filter.value))).json();
      showMessage(data.ready ? '검증·활성화·서비스 ID 조건을 충족합니다. 개별 입력과 선행 데이터는 실행 시 추가 검사합니다.' : data.reasons.join('\n'), data.ready ? 'ok' : 'warn');
    } catch (error) { showMessage(error.message, 'err'); }
  });
  promoteButton.addEventListener('click', async () => {
    if (!selected || !selected.promotable || promoting) return;
    const selection = selected;
    if (!confirm(selection.item.dataset_code + '의 수집 ID ' + selection.item.item_id + '을 업무 테이블에 반영하시겠습니까? 이전 시점의 데이터일 수 있습니다.')) return;
    promoting = true;
    promoteButton.disabled = true;
    filter.disabled = true;
    try {
      const data = await (await krasRequest(selection.promotePath + '?itemId=' + encodeURIComponent(selection.item.item_id), { method: 'POST' })).json();
      await loadHistory();
      showMessage(krasResultMessage(data), krasResultClass(data));
      if (data.success) resetSelection();
    } catch (error) { showMessage(error.message, 'err'); }
    finally {
      promoting = false;
      filter.disabled = false;
      promoteButton.disabled = !selected || !selected.promotable;
    }
  });
  window.refreshKrasHistory = loadHistory;
  loadHistory();
});
