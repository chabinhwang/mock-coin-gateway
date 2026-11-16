// 관리자 콘솔 화면: 탭 라우팅, 대시보드, 사용자(검색·상세), 거래, 초기화권, 결제, 랭킹, 런타임
const PAGE_SIZE = 30;
const TABS = new Set(['dashboard', 'users', 'trading', 'reset-passes', 'billing', 'ranking', 'community', 'runtime', 'raw']);
const TAB_META = {
  dashboard: { title: '대시보드', subtitle: '핵심 지표와 최근 흐름을 확인합니다' },
  users: { title: '사용자', subtitle: '사용자를 검색하고 상세 정보에서 지갑·초기화권·결제를 관리합니다' },
  trading: { title: '거래', subtitle: '사용자 주문 내역을 조회합니다' },
  'reset-passes': { title: '초기화권', subtitle: '초기화권 지급·회수와 원장, 초기화 이력을 관리합니다' },
  billing: { title: '결제', subtitle: '결제 주문을 조회하고 환불·지급 재시도를 처리합니다' },
  ranking: { title: '랭킹', subtitle: '시즌별 랭킹을 조회하고 재계산합니다' },
  community: { title: '게시판', subtitle: '신고를 검수하고 게시글·댓글을 관리합니다' },
  runtime: { title: '런타임', subtitle: '서비스 런타임 설정을 점검합니다' },
  raw: { title: '원본 데이터', subtitle: '대시보드 overview 원본 JSON을 확인합니다' },
};
const PAYMENT_STATUS_LABEL = {
  pending: '대기', paid: '결제 완료', failed: '실패', cancelled: '취소', refund_requested: '환불 요청', refunded: '환불 완료',
};
const SOURCE_LABEL = { admin_grant: '관리자 지급', purchase: '구매' };

const state = {
  overview: null,
  seasons: [],
  selectedSeasonId: null,
  detailUserId: null,
  detail: null,
  usersPageFlags: { walletLookupFailed: false },
  loadedTabs: new Set(),
};
const tables = {};

document.addEventListener('DOMContentLoaded', async () => {
  try {
    const status = unwrap(await request('/api/auth/status'));
    if (!status.authenticated) {
      window.location.href = ADMIN_BASE + '/login.html';
      return;
    }
  } catch (_) {
    window.location.href = ADMIN_BASE + '/login.html';
    return;
  }
  createTables();
  bindEvents();
  Community.init();
  await route();
  await refreshOverview({ silent: true });
});

// ---------------------------------------------------------------------------
// 라우팅: #tab 또는 #users/{userId}
// ---------------------------------------------------------------------------

function parseHash() {
  const [tab, id] = window.location.hash.replace(/^#/, '').split('/');
  return { tab: TABS.has(tab) ? tab : 'dashboard', id };
}

async function route() {
  const { tab, id } = parseHash();
  activateTab(tab);
  if (tab === 'users') {
    const userId = positiveIntOrNull(id);
    showUserDetail(userId);
    if (userId) {
      await loadUserDetail(userId);
    } else {
      document.title = 'MockCoin Admin Console';
      if (!tables.users.loaded) await tables.users.reload({ silent: true });
    }
    return;
  }
  await loadTabOnce(tab);
}

function activateTab(tab) {
  document.querySelectorAll('[data-tab-target]').forEach(link => {
    const active = link.dataset.tabTarget === tab;
    link.classList.toggle('active', active);
    if (active) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });
  document.querySelectorAll('[data-tab-panel]').forEach(panel => {
    panel.classList.toggle('active', panel.dataset.tabPanel === tab);
  });
  const meta = TAB_META[tab];
  document.getElementById('page-title').textContent = meta.title;
  document.getElementById('page-subtitle').textContent = meta.subtitle;
}

// 탭은 처음 열 때만 목록을 불러온다. 이후에는 새로고침·필터·작업 후 갱신으로만 다시 부른다
async function loadTabOnce(tab) {
  if (tab === 'community') {
    Community.activate();
    return;
  }
  if (state.loadedTabs.has(tab)) return;
  state.loadedTabs.add(tab);
  if (tab === 'reset-passes') await Promise.all([tables.passes.reload({ silent: true }), tables.histories.reload({ silent: true })]);
  if (tab === 'billing') await Promise.all([tables.payments.reload({ silent: true }), loadProducts({ silent: true })]);
  if (tab === 'ranking') await loadSeasons({ silent: true });
}

// ---------------------------------------------------------------------------
// 공통 이벤트
// ---------------------------------------------------------------------------

function bindEvents() {
  window.addEventListener('hashchange', route);
  document.getElementById('logout-button').addEventListener('click', logout);
  document.getElementById('refresh-overview').addEventListener('click', event => refreshCurrent(event.currentTarget));
  document.getElementById('load-overview').addEventListener('click', event => withBusy(event.currentTarget, '불러오는 중', () => refreshOverview()));
  document.getElementById('status-dismiss').addEventListener('click', clearStatus);

  document.getElementById('user-search-form').addEventListener('submit', event => {
    event.preventDefault();
    tables.users.reload();
  });
  document.getElementById('user-search-reset').addEventListener('click', () => {
    document.getElementById('user-search-q').value = '';
    tables.users.reload();
  });
  document.getElementById('user-detail-refresh').addEventListener('click', event => {
    withBusy(event.currentTarget, '새로고침 중', () => loadUserDetail(state.detailUserId));
  });
  document.getElementById('user-detail-ensure-wallet').addEventListener('click', event => ensureWallet(event.currentTarget, state.detailUserId));
  document.getElementById('user-detail-grant-form').addEventListener('submit', event => {
    event.preventDefault();
    grantResetPass(event.submitter, state.detailUserId,
      document.getElementById('user-detail-grant-quantity').value,
      document.getElementById('user-detail-grant-expires').value);
  });
  document.getElementById('user-detail-order-state').addEventListener('change', () => tables.detailOrders.reload());

  document.getElementById('order-history-form').addEventListener('submit', event => {
    event.preventDefault();
    tables.orders.reload();
  });
  document.getElementById('grant-pass-form').addEventListener('submit', event => {
    event.preventDefault();
    grantResetPass(event.submitter,
      positiveIntOrNull(document.getElementById('grant-user-id').value),
      document.getElementById('grant-quantity').value,
      document.getElementById('grant-expires-at').value);
  });
  document.getElementById('passes-filter-form').addEventListener('submit', event => {
    event.preventDefault();
    tables.passes.reload();
  });
  document.getElementById('histories-filter-form').addEventListener('submit', event => {
    event.preventDefault();
    tables.histories.reload();
  });
  document.getElementById('payments-filter-form').addEventListener('submit', event => {
    event.preventDefault();
    tables.payments.reload();
  });
  document.getElementById('load-products').addEventListener('click', event => withBusy(event.currentTarget, '불러오는 중', () => loadProducts()));
  document.getElementById('iap-grant-form').addEventListener('submit', event => {
    event.preventDefault();
    manualIapGrant(event.submitter);
  });
  document.getElementById('load-seasons').addEventListener('click', event => withBusy(event.currentTarget, '불러오는 중', () => loadSeasons()));
  document.getElementById('recalculate-season').addEventListener('click', event => recalculateSeason(event.currentTarget));

  // 표 안의 행 버튼은 패널 단위로 위임 처리한다 (게시판 탭은 community.js가 처리)
  ['panel-dashboard', 'panel-users', 'panel-trading', 'panel-reset-passes', 'panel-billing', 'panel-ranking'].forEach(id => {
    document.getElementById(id).addEventListener('click', onRowAction);
  });
}

async function onRowAction(event) {
  const button = event.target.closest('button[data-action]');
  if (!button) return;
  const data = button.dataset;
  switch (data.action) {
    case 'ensure-wallet':
      return ensureWallet(button, Number(data.userId));
    case 'revoke-pass':
      return revokeResetPass(button, Number(data.passId), Number(data.remaining), Number(data.userId));
    case 'refund':
      return refundPayment(button, Number(data.orderId));
    case 'grant-retry':
      return retryGrant(button, Number(data.orderId));
    case 'view-season':
      return selectSeason(Number(data.seasonId));
    default:
      return undefined;
  }
}

async function refreshCurrent(button) {
  const { tab } = parseHash();
  await withBusy(button, '새로고침 중', async () => {
    const jobs = [refreshOverview({ silent: true })];
    if (tab === 'users') jobs.push(state.detailUserId ? loadUserDetail(state.detailUserId) : tables.users.reload({ silent: true }));
    if (tab === 'trading' && tables.orders.loaded) jobs.push(tables.orders.reload({ silent: true }));
    if (tab === 'reset-passes') jobs.push(tables.passes.reload({ silent: true }), tables.histories.reload({ silent: true }));
    if (tab === 'billing') jobs.push(tables.payments.reload({ silent: true }), loadProducts({ silent: true }));
    if (tab === 'ranking') jobs.push(loadSeasons({ silent: true }));
    if (tab === 'community') Community.activate();
    await Promise.all(jobs);
    setStatus('최신 데이터로 갱신했습니다.', 'success');
  });
}

async function logout() {
  await withBusy(document.getElementById('logout-button'), '로그아웃 중', async () => {
    await request('/api/auth/logout', { method: 'POST' });
    window.location.href = ADMIN_BASE + '/login.html';
  });
}

// ---------------------------------------------------------------------------
// 표 정의
// ---------------------------------------------------------------------------

function createTables() {
  tables.users = new PagedTable({
    tableId: 'users-table',
    pagerId: 'users-pager',
    columns: userColumns(),
    emptyText: '조건에 맞는 사용자가 없습니다.',
    fetchPage: async cursor => {
      const page = await getJson(`${API}/users`, { q: document.getElementById('user-search-q').value, cursor, limit: PAGE_SIZE });
      state.usersPageFlags.walletLookupFailed = Boolean(page.walletLookupFailed);
      return page;
    },
    afterRender: () => {
      document.getElementById('users-wallet-warning').classList.toggle('hidden', !state.usersPageFlags.walletLookupFailed);
    },
  });
  tables.orders = new PagedTable({
    tableId: 'orders-table',
    pagerId: 'orders-pager',
    columns: orderColumns(),
    emptyText: '조회된 주문이 없습니다.',
    itemsOf: page => page.orders || [],
    fetchPage: cursor => {
      const userId = positiveIntOrNull(document.getElementById('order-user-id').value);
      if (!userId) return Promise.reject(new Error('사용자 ID를 입력하세요.'));
      return getJson(`${API}/users/${userId}/orders`, { state: document.getElementById('order-state').value, cursor, limit: PAGE_SIZE });
    },
  });
  tables.passes = new PagedTable({
    tableId: 'reset-passes-table',
    pagerId: 'reset-passes-pager',
    columns: resetPassColumns({ showUser: true }),
    emptyText: '초기화권 원장이 없습니다.',
    fetchPage: cursor => getJson(`${API}/reset-passes`, {
      userId: document.getElementById('passes-filter-user').value,
      sourceType: document.getElementById('passes-filter-source').value,
      cursor,
      limit: PAGE_SIZE,
    }),
  });
  tables.histories = new PagedTable({
    tableId: 'reset-history-table',
    pagerId: 'reset-history-pager',
    columns: resetHistoryColumns({ showUser: true }),
    emptyText: '지갑 초기화 이력이 없습니다.',
    fetchPage: cursor => getJson(`${API}/reset-histories`, { userId: document.getElementById('histories-filter-user').value, cursor, limit: PAGE_SIZE }),
  });
  tables.payments = new PagedTable({
    tableId: 'payment-orders-table',
    pagerId: 'payment-orders-pager',
    columns: paymentColumns({ showUser: true }),
    emptyText: '결제 주문이 없습니다.',
    fetchPage: cursor => getJson(`${API}/payment-orders`, {
      userId: document.getElementById('payments-filter-user').value,
      status: document.getElementById('payments-filter-status').value,
      cursor,
      limit: PAGE_SIZE,
    }),
  });
  tables.rankings = new PagedTable({
    tableId: 'season-rankings-table',
    pagerId: 'season-rankings-pager',
    columns: rankingColumns(),
    emptyText: '이 시즌의 랭킹 행이 없습니다.',
    itemsOf: page => page.rankings || [],
    fetchPage: async cursor => {
      const page = await getJson(`${API}/ranking/seasons/${state.selectedSeasonId}/rankings`, { cursor, limit: 50 });
      document.getElementById('season-rankings-total').textContent = `총 ${formatNumber(page.totalCount)}명`;
      return page;
    },
  });
  tables.detailOrders = new PagedTable({
    tableId: 'user-detail-orders',
    pagerId: 'user-detail-orders-pager',
    columns: orderColumns(),
    emptyText: '주문 내역이 없습니다.',
    itemsOf: page => page.orders || [],
    fetchPage: cursor => getJson(`${API}/users/${state.detailUserId}/orders`, {
      state: document.getElementById('user-detail-order-state').value,
      cursor,
      limit: 20,
    }),
  });
  tables.detailPayments = new PagedTable({
    tableId: 'user-detail-payments',
    pagerId: 'user-detail-payments-pager',
    columns: paymentColumns({ showUser: false }),
    emptyText: '결제 주문이 없습니다.',
    fetchPage: cursor => getJson(`${API}/payment-orders`, { userId: state.detailUserId, cursor, limit: 20 }),
  });
  tables.detailPasses = new PagedTable({
    tableId: 'user-detail-passes',
    pagerId: 'user-detail-passes-pager',
    columns: resetPassColumns({ showUser: false }),
    emptyText: '초기화권이 없습니다.',
    fetchPage: cursor => getJson(`${API}/reset-passes`, { userId: state.detailUserId, cursor, limit: 20 }),
  });
  tables.detailHistories = new PagedTable({
    tableId: 'user-detail-histories',
    pagerId: 'user-detail-histories-pager',
    columns: resetHistoryColumns({ showUser: false }),
    emptyText: '지갑 초기화 이력이 없습니다.',
    fetchPage: cursor => getJson(`${API}/reset-histories`, { userId: state.detailUserId, cursor, limit: 20 }),
  });
}

function walletCell(row) {
  if (row.hasWallet === true) return pill('준비됨', 'success');
  if (row.hasWallet === false) return pill('없음', 'warning');
  return pill('확인 불가', 'neutral');
}

function userColumns() {
  return [
    { label: '사용자', render: row => userCell(row.userId, row.nickname), className: 'user-id-cell' },
    { label: '권한', render: row => pill(row.role || 'USER', row.role === 'ADMIN' ? 'warning' : 'neutral') },
    { label: '지갑', render: walletCell },
    { label: 'KRW', render: row => formatMoney(row.krwBalance), className: 'nowrap-cell' },
    { label: '초기화권', render: row => `${formatNumber(row.remainingResetPasses)}장`, className: 'nowrap-cell' },
    { label: '초기화', render: row => `${formatNumber(row.resetCount)}회`, className: 'nowrap-cell' },
    { label: '활성 세션', render: row => formatNumber(row.activeSessionCount), className: 'nowrap-cell' },
    { label: '가입', render: row => dateCell(row.createdAt), className: 'nowrap-cell' },
    { label: '', render: row => rowActions([
      row.hasWallet === false ? actionButton('ensure-wallet', '지갑 생성', { 'user-id': row.userId }) : '',
      `<a class="row-button" href="#users/${encodeURIComponent(row.userId)}">상세</a>`,
    ]), className: 'cell-actions' },
  ];
}

function orderColumns() {
  return [
    { label: '주문', render: row => strongWithSmall(row.market || '-', `#${row.orderId}`) },
    { label: '매수/매도', render: row => pill(row.side === 'bid' || row.side === 'buy' ? '매수' : row.side === 'ask' || row.side === 'sell' ? '매도' : row.side || '-', row.side === 'bid' || row.side === 'buy' ? 'success' : 'warning') },
    { label: '유형', render: row => text(row.orderType) },
    { label: '가격', render: row => formatMoney(row.price), className: 'nowrap-cell' },
    { label: '수량', render: row => formatDecimal(row.volume), className: 'nowrap-cell' },
    { label: '체결', render: row => formatDecimal(row.filledVolume), className: 'nowrap-cell' },
    { label: '잔여', render: row => formatDecimal(row.remainingVolume), className: 'nowrap-cell' },
    { label: '상태', render: row => pill(row.state || '알 수 없음', statusTone(row.state)) },
    { label: '주문 시각', render: row => dateCell(row.createdAt), className: 'nowrap-cell' },
  ];
}

function paymentColumns({ showUser }) {
  const columns = [
    { label: '결제 주문', render: row => strongWithSmall(`#${row.paymentOrderId}`, row.iapOrderId || row.externalPaymentKey || '주문 ID 없음'), className: 'nowrap-cell' },
  ];
  if (showUser) columns.push({ label: '사용자', render: row => userCell(row.userId, row.nickname), className: 'user-id-cell' });
  columns.push(
    { label: 'SKU', render: row => strongWithSmall(row.iapSku || '-', `${formatNumber(row.grantedQuantity)}장`) },
    { label: '상태', render: row => pill(PAYMENT_STATUS_LABEL[row.status] || row.status || '알 수 없음', statusTone(row.status)) },
    { label: '금액', render: row => formatMoney(row.amountKrw), className: 'nowrap-cell' },
    { label: '제공자 상태', render: row => strongWithSmall(row.providerStatus || '-', row.providerReason || '') },
    { label: '생성', render: row => dateCell(row.createdAt), className: 'nowrap-cell' },
    { label: '', render: row => rowActions([
      row.refundable ? actionButton('refund', '환불', { 'order-id': row.paymentOrderId }, 'danger') : '',
      row.status === 'pending' && row.iapOrderId ? actionButton('grant-retry', '지급 재시도', { 'order-id': row.paymentOrderId }) : '',
    ]), className: 'cell-actions' },
  );
  return columns;
}

function resetPassColumns({ showUser }) {
  const columns = [
    { label: '초기화권', render: row => strongWithSmall(SOURCE_LABEL[row.sourceType] || row.sourceType, `#${row.passId}${row.sourceRefId ? ` · 결제 #${row.sourceRefId}` : ''}`) },
  ];
  if (showUser) columns.push({ label: '사용자', render: row => userCell(row.userId, row.nickname), className: 'user-id-cell' });
  columns.push(
    { label: '잔여 / 지급', render: row => `${formatNumber(row.remainingCount)} / ${formatNumber(row.totalCount)}`, className: 'nowrap-cell' },
    { label: '사용', render: row => formatNumber(row.usedCount), className: 'nowrap-cell' },
    { label: '상태', render: row => row.expired ? pill('만료', 'danger') : row.remainingCount > 0 ? pill('사용 가능', 'success') : pill('소진', 'neutral') },
    { label: '만료', render: row => dateCell(row.expiresAt), className: 'nowrap-cell' },
    { label: '지급', render: row => dateCell(row.createdAt), className: 'nowrap-cell' },
    { label: '', render: row => rowActions([
      row.sourceType === 'admin_grant' && row.remainingCount > 0
        ? actionButton('revoke-pass', '회수', { 'pass-id': row.passId, remaining: row.remainingCount, 'user-id': row.userId }, 'warn')
        : '',
    ]), className: 'cell-actions' },
  );
  return columns;
}

function resetHistoryColumns({ showUser }) {
  const columns = [{ label: '이력', render: row => strongWithSmall(row.reason || '초기화', `#${row.historyId}`) }];
  if (showUser) columns.push({ label: '사용자', render: row => userCell(row.userId, row.nickname), className: 'user-id-cell' });
  columns.push(
    { label: '시즌', render: row => text(row.seasonName || (row.seasonId ? `#${row.seasonId}` : '-')) },
    { label: '초기화 전 KRW', render: row => formatMoney(row.beforeKrwBalance), className: 'nowrap-cell' },
    { label: '초기화 전 평가', render: row => formatMoney(row.beforeEquity), className: 'nowrap-cell' },
    { label: '초기화 후 KRW', render: row => formatMoney(row.afterKrwBalance), className: 'nowrap-cell' },
    { label: '결제', render: row => text(row.paymentOrderId ? `#${row.paymentOrderId}` : '-') },
    { label: '시각', render: row => dateCell(row.createdAt), className: 'nowrap-cell' },
  );
  return columns;
}

function rankingColumns() {
  return [
    { label: '순위', render: row => text(row.rankNo ?? row.rank ?? '-'), className: 'nowrap-cell' },
    { label: '사용자', render: row => userCell(row.userId, row.nickname), className: 'user-id-cell' },
    { label: '수익률', render: row => `${formatDecimal(Number(row.totalReturnRate) * 100)}%`, className: 'nowrap-cell' },
    { label: '손익', render: row => formatMoney(row.pnlAmount), className: 'nowrap-cell' },
    { label: '대상', render: row => row.eligible === false ? pill('제외', 'warning') : pill('포함', 'success') },
  ];
}

// ---------------------------------------------------------------------------
// 대시보드 overview
// ---------------------------------------------------------------------------

async function refreshOverview({ silent = false } = {}) {
  try {
    const overview = await getJson(`${API}/overview`);
    state.overview = overview;
    renderOverview(overview);
    document.getElementById('overview-output').textContent = JSON.stringify(overview, null, 2);
  } catch (err) {
    if (!silent) setStatus(err.message, 'error');
    else setStatus(`대시보드를 불러오지 못했습니다: ${err.message}`, 'error');
  }
}

function renderOverview(overview) {
  const counts = overview?.counts || {};
  setText('metric-users', pickNumber(counts, ['users']));
  setText('metric-active-sessions', pickNumber(counts, ['activeSessions']));
  setText('metric-pending-report-targets', pickNumber(counts, ['pendingReportTargets']));
  setText('metric-pending-reports', pickNumber(counts, ['pendingReports']));
  setText('metric-payment-orders', pickNumber(counts, ['paymentOrders']));
  setText('metric-rankings', pickNumber(counts, ['activeSeasonRankings']));
  setText('metric-reset-passes', pickNumber(counts, ['resetPasses']));
  setText('metric-reset-histories', pickNumber(counts, ['resetHistories']));
  setText('metric-published-posts', pickNumber(counts, ['publishedPosts']));
  setText('metric-hidden-posts', pickNumber(counts, ['hiddenPosts']));
  setText('metric-published-comments', pickNumber(counts, ['publishedComments']));
  setText('metric-hidden-comments', pickNumber(counts, ['hiddenComments']));

  const pendingTargets = pickNumber(counts, ['pendingReportTargets']);
  const badge = document.getElementById('nav-report-badge');
  badge.textContent = Number.isFinite(pendingTargets) ? formatter.format(pendingTargets) : '';
  badge.classList.toggle('hidden', !(pendingTargets > 0));

  const degraded = Array.isArray(overview?.degradedServices) ? overview.degradedServices : [];
  const banner = document.getElementById('degraded-banner');
  banner.textContent = degraded.length ? `일부 서비스 응답이 없어 해당 수치를 표시하지 못했습니다: ${degraded.join(', ')}` : '';
  banner.classList.toggle('hidden', degraded.length === 0);

  renderTable('dashboard-users', [
    { label: '사용자', render: row => userCell(row.userId, row.nickname), className: 'user-id-cell' },
    { label: '지갑', render: walletCell },
    { label: 'KRW', render: row => formatMoney(row.krwBalance), className: 'nowrap-cell' },
    { label: '초기화권', render: row => `${formatNumber(row.remainingResetPasses)}장`, className: 'nowrap-cell' },
  ], overview?.users || [], '가입한 사용자가 없습니다.');
  const payments = overview?.paymentOrders || [];
  document.getElementById('dashboard-payments').innerHTML = payments.length
    ? payments.map(order => `
      <div class="compact-row">
        <strong>${escapeHtml(PAYMENT_STATUS_LABEL[order.status] || order.status || '알 수 없음')} · ${escapeHtml(formatMoney(order.amountKrw))}</strong>
        <span>#${escapeHtml(order.paymentOrderId)} · ${userCell(order.userId, order.nickname)} · ${escapeHtml(shortDate(order.createdAt))}</span>
      </div>`).join('')
    : '<div class="empty-state">결제 주문이 없습니다.</div>';
  renderTable('dashboard-rankings', rankingColumns(), overview?.activeSeasonRankings || [], '활성 시즌 랭킹이 없습니다.');

  renderRuntime(overview?.runtime);
  renderNotes(overview?.operatorNotes);
  if (!state.seasons.length && Array.isArray(overview?.rankingSeasons)) {
    state.seasons = overview.rankingSeasons;
    if (!state.selectedSeasonId && overview.activeSeasonId) state.selectedSeasonId = overview.activeSeasonId;
  }
}

// ---------------------------------------------------------------------------
// 결제 상품 · 랭킹
// ---------------------------------------------------------------------------

async function loadProducts({ silent = false } = {}) {
  try {
    const payload = await getJson(`${API}/billing/products`);
    renderTable('products-table', [
      { label: 'SKU', render: row => strongWithSmall(row.providerSku, `#${row.id}`) },
      { label: '유형', render: row => pill(row.productType || '알 수 없음', 'neutral') },
      { label: '지급량', render: row => formatNumber(row.grantAmount), className: 'nowrap-cell' },
      { label: '상태', render: row => row.active ? pill('판매 중', 'success') : pill('중지', 'warning') },
    ], payload.products || [], '결제 상품이 없습니다.');
  } catch (err) {
    renderTable('products-table', [], [], `불러오지 못했습니다: ${err.message}`);
    if (!silent) setStatus(err.message, 'error');
  }
}

async function loadSeasons({ silent = false } = {}) {
  try {
    const payload = await getJson(`${API}/ranking/seasons`);
    state.seasons = payload.seasons || [];
  } catch (err) {
    if (!silent) setStatus(err.message, 'error');
  }
  const active = state.seasons.find(season => String(season.status).toLowerCase() === 'active');
  if (!state.selectedSeasonId || !state.seasons.some(season => season.seasonId === state.selectedSeasonId)) {
    state.selectedSeasonId = active?.seasonId || state.seasons[0]?.seasonId || null;
  }
  renderSeasons();
  if (state.selectedSeasonId) await selectSeason(state.selectedSeasonId);
}

function renderSeasons() {
  renderTable('ranking-seasons-table', [
    { label: '시즌', render: row => strongWithSmall(row.name || '시즌', `#${row.seasonId}`) },
    { label: '상태', render: row => pill(row.status || '알 수 없음', statusTone(row.status)) },
    { label: '시드', render: row => formatMoney(row.seedBalance), className: 'nowrap-cell' },
    { label: '시작', render: row => dateCell(row.startsAt), className: 'nowrap-cell' },
    { label: '종료', render: row => dateCell(row.endsAt), className: 'nowrap-cell' },
    { label: '', render: row => rowActions([
      row.seasonId === state.selectedSeasonId ? '<span class="table-pill success">보는 중</span>' : actionButton('view-season', '랭킹 보기', { 'season-id': row.seasonId }),
    ]), className: 'cell-actions' },
  ], state.seasons, '랭킹 시즌이 없습니다.');
}

async function selectSeason(seasonId) {
  state.selectedSeasonId = seasonId;
  const season = state.seasons.find(item => item.seasonId === seasonId);
  document.getElementById('season-rankings-title').textContent = season ? `${season.name} 랭킹` : `시즌 #${seasonId} 랭킹`;
  document.getElementById('season-rankings-total').textContent = '';
  document.getElementById('recalculate-season').classList.remove('hidden');
  renderSeasons();
  tables.rankings.loaded = false;
  await tables.rankings.reload();
}

async function recalculateSeason(button) {
  const seasonId = state.selectedSeasonId;
  if (!seasonId) return;
  const season = state.seasons.find(item => item.seasonId === seasonId);
  await runConfirmed(button, {
    title: '랭킹을 재계산할까요?',
    message: '현재 시세 기준으로 이 시즌의 순위를 다시 계산합니다. 사용자에게 보이는 순위가 바뀔 수 있습니다.',
    rows: [['시즌', season ? `${season.name} (#${seasonId})` : `#${seasonId}`]],
    confirmLabel: '재계산',
  }, () => postJson(`${API}/ranking/seasons/${seasonId}/recalculate`, null), {
    successMessage: '랭킹을 재계산했습니다.',
    after: async () => {
      await Promise.all([tables.rankings.reload({ silent: true }), refreshOverview({ silent: true })]);
    },
  });
}

// ---------------------------------------------------------------------------
// 런타임
// ---------------------------------------------------------------------------

function renderRuntime(runtime) {
  document.getElementById('runtime-list').innerHTML = [
    runtimeCard('인증', runtime?.auth, ['tossMockEnabled', 'tossBaseUrl', 'jwtSecretConfigured', 'adminTossUserKeys']),
    runtimeCard('결제', runtime?.billing, ['iapMockEnabled', 'iapBaseUrl', 'iapOrderStatusPath', 'iapRefundPath']),
    runtimeCard('주문', runtime?.order, ['maxPriceDeviation']),
    runtimeCard('보안', runtime?.security, ['secureAdminCookie', 'adminSessionTimeoutSeconds', 'adminMaxLoginAttempts', 'passkeyEnabled', 'webauthnRpId']),
    runtimeCard('웹소켓', runtime?.websocket, ['maxGlobalConnections', 'maxMessagesPerSecond', 'pingIntervalSeconds', 'maxSubscriptionsPerSession']),
    runtimeCard('연동 상태', runtime?.integrations, ['upbitRestBaseUrl', 'ingestorEnabled', 'gatewayInternalEventTokenConfigured', 'gatewayRedisPrivateEventsEnabled', 'communityInternalTokenConfigured']),
  ].join('');
}

function runtimeCard(title, source, keys) {
  const rows = keys
    .filter(key => source && !isEmptyValue(source[key]))
    .map(key => `<div><span>${escapeHtml(labelize(key))}</span><code>${escapeHtml(formatValue(source[key]))}</code></div>`);
  const loaded = source && Object.keys(source).length > 0;
  return `
    <article class="runtime-card ${loaded ? 'is-live' : 'is-waiting'}">
      <header><strong>${escapeHtml(title)}</strong><em>${loaded ? '응답' : '응답 없음'}</em></header>
      <div class="runtime-pairs">${rows.length ? rows.join('') : '<p>서비스에서 받은 설정이 없습니다.</p>'}</div>
    </article>`;
}

function renderNotes(notes) {
  const container = document.getElementById('operator-notes');
  const list = Array.isArray(notes) ? notes.slice(0, 6) : [];
  container.innerHTML = list.length
    ? list.map(note => `<article class="note-card"><strong>${escapeHtml(note.title || '노트')}</strong><span>${escapeHtml(note.detail || '')}</span></article>`).join('')
    : '<div class="empty-state">운영자 노트가 없습니다.</div>';
}
