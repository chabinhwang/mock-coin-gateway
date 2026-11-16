// 관리자 콘솔 공통 모듈: API 호출, 상태 배너, 표/더 보기 페이지, 확인 대화상자, 포맷터
const ADMIN_BASE = (() => {
  const path = window.location.pathname
    .replace(/\/index\.html$/, '')
    .replace(/\/login\.html$/, '')
    .replace(/\/+$/, '');
  return path === '/' ? '' : path;
})();
const API = '/api/v1/admin-console';

const formatter = new Intl.NumberFormat('ko-KR');
const decimalFormatter = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 8 });

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

async function request(path, options = {}) {
  const headers = { Accept: 'application/json', ...(options.headers || {}) };
  const response = await fetch(ADMIN_BASE + path, { ...options, headers, credentials: 'same-origin' });
  if (response.redirected && /login\.html(?:\?|$)/.test(response.url)) {
    window.location.href = ADMIN_BASE + '/login.html';
    throw new Error('인증이 필요합니다.');
  }
  if (response.status === 401 || response.status === 302) {
    window.location.href = ADMIN_BASE + '/login.html';
    throw new Error('인증이 필요합니다.');
  }
  const body = parseJsonBody(await response.text());
  if (!response.ok) {
    throw new Error(errorMessageFromBody(body, response.status));
  }
  return body;
}

async function getJson(path, params = {}) {
  return unwrap(await request(path + queryString(params)));
}

async function postJson(path, body) {
  return unwrap(await request(path, {
    method: 'POST',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  }));
}

function queryString(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      search.set(key, String(value).trim());
    }
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

function errorMessageFromBody(body, status) {
  const error = body?.error;
  if (error && typeof error === 'object' && error.reason) return error.reason;
  if (typeof error === 'string' && error.trim()) return error;
  if (typeof body?.detail === 'string' && body.detail.trim()) return body.detail;
  if (typeof body?.message === 'string' && body.message.trim()) return body.message;
  return `요청 실패 (${status})`;
}

function parseJsonBody(textBody) {
  if (!textBody) return null;
  try {
    return JSON.parse(textBody);
  } catch (_) {
    throw new Error('관리 API가 JSON이 아닌 응답을 반환했습니다.');
  }
}

function unwrap(body) { return body?.success ?? body; }

async function withBusy(button, busyLabel, task) {
  if (!button) return task();
  if (button.disabled) return undefined;
  const original = button.textContent;
  button.disabled = true;
  button.textContent = busyLabel;
  try {
    return await task();
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

// ---------------------------------------------------------------------------
// 상태 배너
// ---------------------------------------------------------------------------

const STATUS_HIDE_MS = { success: 2500, info: 3000, error: 6000 };
let statusHideTimer = 0;

function setStatus(message, type = 'info') {
  const element = document.getElementById('status');
  const textNode = document.getElementById('status-message');
  if (!element || !textNode) return;
  window.clearTimeout(statusHideTimer);
  textNode.textContent = message;
  element.className = `status-banner ${type}`;
  statusHideTimer = window.setTimeout(clearStatus, STATUS_HIDE_MS[type] || 3000);
}

function clearStatus() {
  const element = document.getElementById('status');
  if (!element) return;
  window.clearTimeout(statusHideTimer);
  statusHideTimer = 0;
  element.className = 'status-banner hidden';
}

// ---------------------------------------------------------------------------
// 확인 대화상자: 되돌리기 어렵거나 원장에 기록되는 작업은 반드시 여기를 거친다
// ---------------------------------------------------------------------------

// rows: [label, value] 목록. danger면 확인 버튼을 위험 색으로 표시한다.
// input({ label, min, max, value })을 주면 정수 입력을 받아 범위를 검증한다.
// 취소하면 false, 확인하면 true(input이 있으면 입력한 정수)를 반환한다
function confirmAction({ title, message, rows = [], confirmLabel = '실행', danger = false, input = null }) {
  const dialog = document.getElementById('confirm-dialog');
  document.getElementById('confirm-title').textContent = title;
  document.getElementById('confirm-message').textContent = message || '';
  document.getElementById('confirm-rows').innerHTML = rows
    .filter(([, value]) => !isEmptyValue(value))
    .map(([label, value]) => `<div><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>`)
    .join('');
  const inputWrap = document.getElementById('confirm-input-wrap');
  const inputField = document.getElementById('confirm-input');
  const inputError = document.getElementById('confirm-input-error');
  inputWrap.classList.toggle('hidden', !input);
  inputError.textContent = '';
  if (input) {
    document.getElementById('confirm-input-label').textContent = input.label;
    inputField.min = String(input.min ?? 1);
    inputField.max = input.max === undefined ? '' : String(input.max);
    inputField.value = String(input.value ?? '');
  }
  const confirmButton = document.getElementById('confirm-accept');
  confirmButton.textContent = confirmLabel;
  confirmButton.classList.toggle('danger-button', danger);
  confirmButton.classList.toggle('primary-button', !danger);
  return new Promise(resolve => {
    let accepted = null;
    const finish = result => {
      dialog.removeEventListener('close', onClose);
      confirmButton.removeEventListener('click', onAccept);
      inputField.removeEventListener('keydown', onInputKey);
      resolve(result);
    };
    // 입력칸에서 Enter를 누르면 폼 기본 제출(취소 버튼)이 아니라 확인으로 처리한다
    const onInputKey = event => {
      if (event.key === 'Enter') {
        event.preventDefault();
        onAccept();
      }
    };
    const onAccept = () => {
      if (input) {
        const value = Number(inputField.value);
        const min = input.min ?? 1;
        if (!Number.isInteger(value) || value < min || (input.max !== undefined && value > input.max)) {
          inputError.textContent = input.max === undefined
            ? `${min} 이상의 정수를 입력하세요.`
            : `${min}~${input.max} 사이의 정수를 입력하세요.`;
          inputField.focus();
          return;
        }
        accepted = value;
      } else {
        accepted = true;
      }
      dialog.returnValue = 'confirm';
      dialog.close();
    };
    const onClose = () => finish(dialog.returnValue === 'confirm' ? accepted : false);
    dialog.returnValue = '';
    confirmButton.addEventListener('click', onAccept);
    inputField.addEventListener('keydown', onInputKey);
    dialog.addEventListener('close', onClose);
    dialog.showModal();
    (input ? inputField : document.getElementById('confirm-cancel')).focus();
  });
}

// 확인 → 실행 → 결과 배너 → 후속 갱신을 한 번에 처리한다.
// 실패해도 서버 상태가 일부 바뀌었을 수 있으므로(예: 환불 요청 상태로 남음) 후속 갱신은 항상 실행한다
async function runConfirmed(button, confirmOptions, task, { successMessage = '처리했습니다.', after } = {}) {
  const confirmed = await confirmAction(confirmOptions);
  if (confirmed === false) return false;
  let succeeded = false;
  await withBusy(button, '처리 중', async () => {
    try {
      await task(confirmed);
      succeeded = true;
      setStatus(successMessage, 'success');
    } catch (err) {
      setStatus(err.message, 'error');
    }
  });
  if (after) await after();
  return succeeded;
}

// ---------------------------------------------------------------------------
// 표와 "더 보기" 페이지
// ---------------------------------------------------------------------------

function renderTable(target, columns, rows, emptyText) {
  const element = typeof target === 'string' ? document.getElementById(target) : target;
  if (!element) return;
  if (!rows || rows.length === 0) {
    element.innerHTML = `<div class="empty-state">${escapeHtml(emptyText)}</div>`;
    return;
  }
  const header = columns.map(column => `<th class="${escapeHtml(column.className || '')}">${escapeHtml(column.label)}</th>`).join('');
  const body = rows.map(row => (
    `<tr>${columns.map(column => `<td class="${escapeHtml(column.className || '')}">${column.render(row)}</td>`).join('')}</tr>`
  )).join('');
  element.innerHTML = `<table class="data-table"><thead><tr>${header}</tr></thead><tbody>${body}</tbody></table>`;
}

// cursor 기반 목록: 첫 페이지 로드·다음 페이지 이어 붙이기·필터 변경 시 초기화를 담당한다
class PagedTable {
  constructor({ tableId, pagerId, columns, emptyText, fetchPage, itemsOf = page => page.items || [], afterRender }) {
    this.tableId = tableId;
    this.pagerId = pagerId;
    this.columns = columns;
    this.emptyText = emptyText;
    this.fetchPage = fetchPage;
    this.itemsOf = itemsOf;
    this.afterRender = afterRender;
    this.rows = [];
    this.nextCursor = null;
    this.hasMore = false;
    this.loaded = false;
    this.requestSeq = 0;
    const pager = document.getElementById(pagerId);
    this.moreButton = pager ? pager.querySelector('[data-pager-more]') : null;
    this.summary = pager ? pager.querySelector('[data-pager-summary]') : null;
    this.moreButton?.addEventListener('click', () => this.loadMore());
  }

  async reload({ silent = false } = {}) {
    const seq = ++this.requestSeq;
    this.setLoading(true);
    try {
      const page = await this.fetchPage(null);
      if (seq !== this.requestSeq) return;
      this.rows = this.itemsOf(page);
      this.applyPage(page);
      this.loaded = true;
      this.render();
    } catch (err) {
      if (seq !== this.requestSeq) return;
      this.rows = [];
      this.nextCursor = null;
      this.hasMore = false;
      renderTable(this.tableId, this.columns, [], `불러오지 못했습니다: ${err.message}`);
      this.updatePager();
      if (!silent) setStatus(err.message, 'error');
    } finally {
      if (seq === this.requestSeq) this.setLoading(false);
    }
  }

  async loadMore() {
    if (!this.hasMore || !this.nextCursor) return;
    const seq = ++this.requestSeq;
    await withBusy(this.moreButton, '불러오는 중', async () => {
      try {
        const page = await this.fetchPage(this.nextCursor);
        if (seq !== this.requestSeq) return;
        this.rows = this.rows.concat(this.itemsOf(page));
        this.applyPage(page);
        this.render();
      } catch (err) {
        setStatus(err.message, 'error');
      }
    });
  }

  applyPage(page) {
    this.nextCursor = page?.nextCursor ?? null;
    this.hasMore = Boolean(page?.hasMore && this.nextCursor);
  }

  render() {
    renderTable(this.tableId, this.columns, this.rows, this.emptyText);
    this.updatePager();
    if (this.afterRender) this.afterRender(this.rows);
  }

  updatePager() {
    if (this.moreButton) this.moreButton.classList.toggle('hidden', !this.hasMore);
    if (this.summary) {
      this.summary.textContent = this.rows.length
        ? `${formatter.format(this.rows.length)}건 표시${this.hasMore ? ' · 더 있음' : ' · 마지막'}`
        : '';
    }
  }

  setLoading(loading) {
    const element = document.getElementById(this.tableId);
    if (element) element.classList.toggle('is-loading', loading);
    if (loading && !this.loaded && element) {
      element.innerHTML = '<div class="empty-state">불러오는 중…</div>';
    }
  }
}

// ---------------------------------------------------------------------------
// 셀 렌더러와 포맷터
// ---------------------------------------------------------------------------

// 사용자 셀: 닉네임과 ID를 보여주고 클릭하면 사용자 상세로 이동한다
function userCell(userId, nickname) {
  if (isEmptyValue(userId)) return '—';
  const label = nickname || `user-${userId}`;
  return `<a class="user-link" href="#users/${encodeURIComponent(userId)}"><strong>${escapeHtml(label)}</strong><small>#${escapeHtml(userId)}</small></a>`;
}

function rowActions(buttons) {
  const content = buttons.filter(Boolean).join('');
  return content ? `<div class="row-actions">${content}</div>` : '<span class="muted">—</span>';
}

function actionButton(action, label, data = {}, tone = '', title = '') {
  const attributes = Object.entries(data)
    .map(([key, value]) => `data-${escapeHtml(key)}="${escapeHtml(value)}"`)
    .join(' ');
  const titleAttribute = title ? ` title="${escapeHtml(title)}"` : '';
  return `<button class="row-button ${tone}" type="button" data-action="${escapeHtml(action)}" ${attributes}${titleAttribute}>${escapeHtml(label)}</button>`;
}

function setText(id, value) {
  const target = document.getElementById(id);
  if (!target) return;
  target.textContent = Number.isFinite(value) ? formatter.format(value) : '—';
}

function pickNumber(source, keys) {
  for (const key of keys) {
    const value = Number(source?.[key]);
    if (source && key in source && Number.isFinite(value)) return value;
  }
  return NaN;
}

function labelize(key) {
  return key.replace(/([A-Z])/g, ' $1').replace(/^./, char => char.toUpperCase());
}

function isEmptyValue(value) {
  return value === undefined || value === null || value === '';
}

function formatValue(value) {
  if (typeof value === 'boolean') return value ? 'on' : 'off';
  if (typeof value === 'number') return formatter.format(value);
  if (Array.isArray(value)) return `${value.length}개 항목`;
  if (value && typeof value === 'object') return '설정됨';
  return String(value);
}

function formatNumber(value) {
  const number = Number(value);
  return isEmptyValue(value) || !Number.isFinite(number) ? '—' : formatter.format(number);
}

function formatDecimal(value) {
  const number = Number(value);
  return isEmptyValue(value) || !Number.isFinite(number) ? '—' : decimalFormatter.format(number);
}

function formatMoney(value) {
  const number = Number(value);
  return isEmptyValue(value) || !Number.isFinite(number) ? '—' : `${formatter.format(Math.round(number))} KRW`;
}

// 날짜 셀은 운영자가 읽는 한국 시간으로 날짜·시각 두 줄로 보여준다
function dateCell(value) {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return escapeHtml(String(value));
  const [date, time] = kstParts(parsed);
  return `<span class="stack-value">${escapeHtml(date)}</span><small>${escapeHtml(time)} KST</small>`;
}

function shortDate(value) {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return String(value);
  const [date, time] = kstParts(parsed);
  return `${date} ${time} KST`;
}

function kstParts(date) {
  const shifted = new Date(date.getTime() + 9 * 60 * 60 * 1000).toISOString();
  return [shifted.slice(0, 10), shifted.slice(11, 19)];
}

// datetime-local 입력(한국 시간)을 서버가 받는 ISO-8601 UTC 문자열로 바꾼다
function kstLocalInputToIso(value) {
  if (!value) return null;
  const parsed = new Date(`${value}:00+09:00`);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

function statusTone(status) {
  const normalized = String(status || '').toLowerCase();
  if (['active', 'paid', 'filled', 'published', 'resolved'].includes(normalized)) return 'success';
  if (['pending', 'scheduled', 'wait', 'partially_filled', 'hidden', 'refund_requested'].includes(normalized)) return 'warning';
  if (['failed', 'rejected', 'deleted', 'refunded', 'cancelled', 'dismissed'].includes(normalized)) return 'danger';
  return 'neutral';
}

function text(value) {
  return escapeHtml(isEmptyValue(value) ? '—' : value);
}

function pill(value, tone = 'neutral') {
  return `<span class="table-pill ${tone}">${escapeHtml(formatValue(value))}</span>`;
}

function strongWithSmall(title, detail) {
  return `<strong class="cell-title">${escapeHtml(title || '—')}</strong><small>${escapeHtml(detail || '')}</small>`;
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function positiveIntOrNull(value) {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 ? number : null;
}
