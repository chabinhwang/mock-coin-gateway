// 사용자 상세 화면과 쓰기 작업(지갑 생성, 초기화권 지급·회수, 환불, 지급 재시도).
// app.js의 state·tables를 사용하며, 모든 쓰기 작업은 확인 대화상자를 거친다.

// ---------------------------------------------------------------------------
// 사용자 상세
// ---------------------------------------------------------------------------

function showUserDetail(userId) {
  state.detailUserId = userId;
  document.getElementById('users-list-view').classList.toggle('hidden', Boolean(userId));
  document.getElementById('user-detail-view').classList.toggle('hidden', !userId);
  if (userId) document.getElementById('page-subtitle').textContent = `사용자 #${userId} 상세`;
}

async function loadUserDetail(userId) {
  if (!userId) return;
  const profile = document.getElementById('user-detail-profile');
  profile.innerHTML = '<div class="empty-state">불러오는 중…</div>';
  try {
    const detail = await getJson(`${API}/users/${userId}`);
    if (state.detailUserId !== userId) return;
    state.detail = detail;
    renderUserDetail(detail);
  } catch (err) {
    document.getElementById('user-detail-title').textContent = `사용자 #${userId}`;
    profile.innerHTML = `<div class="empty-state">${escapeHtml(err.message)}</div>`;
    document.getElementById('user-detail-wallet').innerHTML = '';
    document.getElementById('user-detail-positions').innerHTML = '';
    [tables.detailOrders, tables.detailPayments, tables.detailPasses, tables.detailHistories].forEach(table => {
      table.rows = [];
      table.hasMore = false;
      renderTable(table.tableId, table.columns, [], '사용자를 불러오지 못했습니다.');
      table.updatePager();
    });
    document.getElementById('user-detail-ensure-wallet').classList.add('hidden');
    setStatus(err.message, 'error');
    return;
  }
  await Promise.all([
    tables.detailOrders.reload({ silent: true }),
    tables.detailPayments.reload({ silent: true }),
    tables.detailPasses.reload({ silent: true }),
    tables.detailHistories.reload({ silent: true }),
  ]);
}

function renderUserDetail(detail) {
  const user = detail.user || {};
  const name = user.nickname || `user-${user.userId}`;
  document.getElementById('user-detail-title').textContent = `${name} · #${user.userId}`;
  document.title = `${name} · MockCoin Admin`;
  renderDetailItems('user-detail-profile', [
    ['사용자 ID', `#${user.userId}`, 'mono'],
    ['닉네임', user.nickname || '미설정'],
    ['권한', user.role || 'USER'],
    ['tossUserKey', user.tossUserKey, 'mono wide'],
    ['사용 가능 초기화권', `${formatNumber(user.remainingResetPasses)}장`],
    ['지갑 초기화', `${formatNumber(user.resetCount)}회${user.lastResetAt ? ` · 마지막 ${shortDate(user.lastResetAt)}` : ''}`],
    ['활성 세션', formatNumber(user.activeSessionCount)],
    ['가입', shortDate(user.createdAt), 'mono'],
  ]);

  const portfolio = detail.portfolio;
  const ensureButton = document.getElementById('user-detail-ensure-wallet');
  if (detail.portfolioUnavailable || !portfolio) {
    renderDetailItems('user-detail-wallet', [['상태', 'trading-service 응답이 없어 지갑을 확인하지 못했습니다.', 'wide']]);
    document.getElementById('user-detail-positions').innerHTML = '';
    ensureButton.classList.add('hidden');
    return;
  }
  ensureButton.classList.toggle('hidden', portfolio.hasWallet);
  if (!portfolio.hasWallet) {
    renderDetailItems('user-detail-wallet', [['상태', '지갑이 없습니다. 로그인 중 지갑 생성이 실패했을 수 있습니다.', 'wide']]);
    document.getElementById('user-detail-positions').innerHTML = '';
    return;
  }
  const costBasis = (portfolio.positions || []).reduce((sum, position) => sum + (Number(position.costBasis) || 0), 0);
  renderDetailItems('user-detail-wallet', [
    ['KRW 잔액', formatMoney(portfolio.krwBalance)],
    ['시드', formatMoney(portfolio.seedKrwBalance)],
    ['보유 코인 매입 금액', formatMoney(costBasis)],
    ['지갑 갱신', shortDate(portfolio.walletUpdatedAt), 'mono'],
  ]);
  renderTable('user-detail-positions', [
    { label: '마켓', render: row => text(row.market) },
    { label: '수량', render: row => formatDecimal(row.quantity), className: 'nowrap-cell' },
    { label: '평균 단가', render: row => formatMoney(row.avgEntryPrice), className: 'nowrap-cell' },
    { label: '매입 금액', render: row => formatMoney(row.costBasis), className: 'nowrap-cell' },
  ], portfolio.positions || [], '보유 코인이 없습니다.');
}

function renderDetailItems(targetId, rows) {
  document.getElementById(targetId).innerHTML = rows.map(([label, value, modifier]) => (
    `<article class="detail-item ${modifier || ''}"><strong>${escapeHtml(label)}</strong><span>${escapeHtml(isEmptyValue(value) ? '—' : value)}</span></article>`
  )).join('');
}

async function refreshUserViews(userId) {
  const jobs = [refreshOverview({ silent: true })];
  if (state.detailUserId && (!userId || state.detailUserId === userId)) jobs.push(loadUserDetail(state.detailUserId));
  if (tables.users.loaded) jobs.push(tables.users.reload({ silent: true }));
  if (tables.passes.loaded) jobs.push(tables.passes.reload({ silent: true }));
  if (tables.payments.loaded) jobs.push(tables.payments.reload({ silent: true }));
  await Promise.all(jobs);
}

// ---------------------------------------------------------------------------
// 쓰기 작업 (모두 확인 대화상자를 거친다)
// ---------------------------------------------------------------------------

function findUserRow(userId) {
  if (state.detail?.user?.userId === userId) return state.detail.user;
  return tables.users.rows.find(row => row.userId === userId)
    || (state.overview?.users || []).find(row => row.userId === userId);
}

function userLabel(userId) {
  const row = findUserRow(userId);
  return row?.nickname ? `${row.nickname} (#${userId})` : `#${userId}`;
}

async function ensureWallet(button, userId) {
  if (!userId) return;
  await runConfirmed(button, {
    title: '지갑을 생성할까요?',
    message: '지갑이 이미 있으면 그대로 두고, 없으면 시드 잔액으로 새로 만듭니다.',
    rows: [['사용자', userLabel(userId)]],
    confirmLabel: '지갑 생성',
  }, () => postJson(`${API}/users/${userId}/wallet/ensure`, null), {
    successMessage: '지갑을 확인했습니다.',
    after: () => refreshUserViews(userId),
  });
}

async function grantResetPass(button, userId, quantityValue, expiresValue) {
  const quantity = positiveIntOrNull(quantityValue);
  if (!userId) return setStatus('대상 사용자 ID를 입력하세요.', 'error');
  if (!quantity || quantity > 100) return setStatus('수량은 1~100 사이 정수여야 합니다.', 'error');
  const expiresAt = kstLocalInputToIso(expiresValue);
  if (expiresValue && !expiresAt) return setStatus('만료 시각 형식이 올바르지 않습니다.', 'error');
  if (expiresAt && new Date(expiresAt) <= new Date()) return setStatus('만료 시각은 현재 이후여야 합니다.', 'error');
  await runConfirmed(button, {
    title: '초기화권을 지급할까요?',
    message: '관리자 지급분은 원장에 기록되며, 사용하지 않은 수량만 나중에 회수할 수 있습니다.',
    rows: [
      ['사용자', userLabel(userId)],
      ['수량', `${quantity}장`],
      ['만료', expiresAt ? shortDate(expiresAt) : '없음'],
    ],
    confirmLabel: '지급',
  }, () => postJson(`${API}/users/${userId}/reset-passes`, expiresAt ? { quantity, expiresAt } : { quantity }), {
    successMessage: `초기화권 ${quantity}장을 지급했습니다.`,
    after: () => refreshUserViews(userId),
  });
}

async function revokeResetPass(button, passId, remaining, userId) {
  await runConfirmed(button, {
    title: '초기화권을 회수할까요?',
    message: '사용하지 않은 관리자 지급분만 회수됩니다.',
    rows: [['초기화권', `#${passId}`], ['사용자', userId ? userLabel(userId) : null], ['회수 가능', `${remaining}장`]],
    confirmLabel: '회수',
    danger: true,
    input: { label: '회수 수량', min: 1, max: remaining, value: remaining },
  }, quantity => postJson(`${API}/reset-passes/${passId}/revoke`, { quantity }), {
    successMessage: '초기화권을 회수했습니다.',
    after: () => refreshUserViews(userId),
  });
}

function findPaymentRow(orderId) {
  return [tables.payments, tables.detailPayments]
    .flatMap(table => table.rows)
    .find(row => row.paymentOrderId === orderId);
}

async function refundPayment(button, orderId) {
  const order = findPaymentRow(orderId);
  if (!order) return;
  await runConfirmed(button, {
    title: '이 결제를 환불할까요?',
    message: 'Toss IAP 환불을 요청하고, 지급된 초기화권을 회수합니다. 되돌릴 수 없습니다.',
    rows: [
      ['결제 주문', `#${order.paymentOrderId}`],
      ['Toss 주문 ID', order.iapOrderId],
      ['사용자', userLabel(order.userId)],
      ['금액', formatMoney(order.amountKrw)],
      ['SKU', order.iapSku],
    ],
    confirmLabel: '환불',
    danger: true,
  }, () => postJson(`${API}/iap/refund`, { userId: order.userId, purchaseId: order.paymentOrderId }), {
    successMessage: '환불을 처리했습니다.',
    after: () => refreshUserViews(order.userId),
  });
}

async function retryGrant(button, orderId) {
  const order = findPaymentRow(orderId);
  if (!order) return;
  await runConfirmed(button, {
    title: '지급을 다시 시도할까요?',
    message: 'Toss 결제 상태를 다시 조회해 결제가 완료됐으면 초기화권을 지급합니다. 이미 지급된 주문은 중복 지급하지 않습니다.',
    rows: [
      ['결제 주문', `#${order.paymentOrderId}`],
      ['Toss 주문 ID', order.iapOrderId],
      ['사용자', userLabel(order.userId)],
      ['SKU', order.iapSku],
    ],
    confirmLabel: '지급 재시도',
  }, () => postJson(`${API}/iap/grant`, { userId: order.userId, orderId: order.iapOrderId, sku: order.iapSku || undefined }), {
    successMessage: '지급 재시도를 요청했습니다.',
    after: () => refreshUserViews(order.userId),
  });
}

async function manualIapGrant(button) {
  const userId = positiveIntOrNull(document.getElementById('iap-grant-user-id').value);
  const orderId = document.getElementById('iap-grant-order-id').value.trim();
  const sku = document.getElementById('iap-grant-sku').value.trim();
  const purchaseToken = document.getElementById('iap-grant-token').value.trim();
  if (!userId || !orderId) return setStatus('사용자 ID와 Toss 주문 ID를 입력하세요.', 'error');
  await runConfirmed(button, {
    title: 'IAP 지급을 시도할까요?',
    message: 'Toss 결제 상태를 조회해 결제가 완료된 주문만 지급합니다.',
    rows: [['사용자', userLabel(userId)], ['Toss 주문 ID', orderId], ['SKU', sku || null]],
    confirmLabel: '지급 재시도',
  }, () => postJson(`${API}/iap/grant`, Object.fromEntries(Object.entries({ userId, orderId, sku, purchaseToken }).filter(([, value]) => value))), {
    successMessage: 'IAP 지급을 요청했습니다.',
    after: () => refreshUserViews(userId),
  });
}
