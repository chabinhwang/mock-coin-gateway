// 게시판 탭: 신고 대상별 검수(인정·기각 + 콘텐츠 조치), 콘텐츠 목록, ID 조회
const Community = (() => {
  const PAGE_SIZE = 30;
  const TARGET_LABEL = { post: '게시글', comment: '댓글' };
  const RESOLUTION_LABEL = { none: '조치 없음', hide: '숨김', delete: '삭제', unhide: '숨김 해제' };
  const DECISION_HINTS = {
    hide: '신고를 인정하고 콘텐츠를 숨깁니다',
    delete: '신고를 인정하고 콘텐츠를 삭제합니다',
    accept: '콘텐츠는 그대로 두고 신고만 인정합니다',
    dismiss: '콘텐츠는 그대로 두고 신고를 기각합니다',
    restore: '신고를 기각하고 숨김을 해제합니다',
  };
  const DECISIONS = {
    hide: { decision: 'resolve', contentAction: 'hide', label: '숨김', title: '신고를 인정하고 숨길까요?', confirmLabel: '숨김 처리', danger: false },
    delete: { decision: 'resolve', contentAction: 'delete', label: '삭제', title: '신고를 인정하고 삭제할까요?', confirmLabel: '삭제', danger: true },
    accept: { decision: 'resolve', contentAction: 'none', label: '인정', title: '콘텐츠는 그대로 두고 신고를 인정할까요?', confirmLabel: '인정', danger: false },
    dismiss: { decision: 'dismiss', contentAction: 'none', label: '기각', title: '신고를 기각할까요?', confirmLabel: '기각', danger: false },
    restore: { decision: 'dismiss', contentAction: 'unhide', label: '기각·복구', title: '신고를 기각하고 숨김을 해제할까요?', confirmLabel: '기각하고 복구', danger: false },
  };
  const CONTENT_LISTS = {
    'published-posts': { kind: 'post', actions: ['hide', 'delete'], empty: '공개 게시글이 없습니다.' },
    'hidden-posts': { kind: 'post', actions: ['unhide', 'delete'], empty: '숨김 게시글이 없습니다.' },
    'published-comments': { kind: 'comment', actions: ['hide', 'delete'], empty: '공개 댓글이 없습니다.' },
    'hidden-comments': { kind: 'comment', actions: ['unhide', 'delete'], empty: '숨김 댓글이 없습니다.' },
  };
  const CONTENT_ACTIONS = {
    hide: { label: '숨김', verb: '숨길까요?', confirmLabel: '숨김', danger: false },
    unhide: { label: '숨김 해제', verb: '다시 공개할까요?', confirmLabel: '숨김 해제', danger: false },
    delete: { label: '삭제', verb: '삭제할까요? 사용자 화면에서 되돌릴 수 없습니다.', confirmLabel: '삭제', danger: true },
  };

  let reportStatus = 'pending';
  let contentList = 'published-posts';
  let selectedTarget = null;
  let reportTargets;
  let reportDetail;
  let contents;
  let initialized = false;

  function init() {
    reportTargets = new PagedTable({
      tableId: 'report-targets-table',
      pagerId: 'report-targets-pager',
      columns: reportTargetColumns(),
      emptyText: '해당 상태의 신고가 없습니다.',
      fetchPage: cursor => getJson(`${API}/community/report-targets`, { status: reportStatus, cursor, limit: PAGE_SIZE }),
    });
    reportDetail = new PagedTable({
      tableId: 'report-detail-table',
      pagerId: 'report-detail-pager',
      columns: [
        { label: '신고자', render: row => userCell(row.reporterId, row.reporterNickname), className: 'user-id-cell' },
        { label: '상태', render: row => pill(reportStatusLabel(row.status), statusTone(row.status)) },
        { label: '조치', render: row => text(RESOLUTION_LABEL[row.resolution] || row.resolution) },
        { label: '신고 시각', render: row => dateCell(row.createdAt), className: 'nowrap-cell' },
        { label: '처리 시각', render: row => dateCell(row.processedAt), className: 'nowrap-cell' },
      ],
      emptyText: '신고가 없습니다.',
      fetchPage: cursor => getJson(`${API}/community/report-targets/${selectedTarget.targetType}/${selectedTarget.targetId}/reports`, { cursor, limit: PAGE_SIZE }),
    });
    contents = new PagedTable({
      tableId: 'community-content-table',
      pagerId: 'community-content-pager',
      columns: contentColumns(),
      emptyText: CONTENT_LISTS[contentList].empty,
      fetchPage: cursor => getJson(`${API}/community/${contentList}`, { cursor, limit: PAGE_SIZE }),
    });

    document.querySelectorAll('[data-report-status]').forEach(button => {
      button.addEventListener('click', () => {
        reportStatus = button.dataset.reportStatus;
        selectSegment('[data-report-status]', button);
        reportTargets.columns = reportTargetColumns();
        reportTargets.reload();
      });
    });
    document.querySelectorAll('[data-content-list]').forEach(button => {
      button.addEventListener('click', () => {
        contentList = button.dataset.contentList;
        selectSegment('[data-content-list]', button);
        contents.columns = contentColumns();
        contents.emptyText = CONTENT_LISTS[contentList].empty;
        contents.reload();
      });
    });
    document.getElementById('report-detail-close').addEventListener('click', closeReportDetail);
    document.getElementById('community-lookup-form').addEventListener('submit', async event => {
      event.preventDefault();
      await lookup(document.getElementById('community-lookup-kind').value, document.getElementById('community-lookup-id').value, event.submitter);
    });
    document.getElementById('panel-community').addEventListener('click', onActionClick);
    initialized = true;
  }

  function activate() {
    if (!initialized) return;
    reportTargets.reload({ silent: true });
    contents.reload({ silent: true });
  }

  function selectSegment(selector, active) {
    document.querySelectorAll(selector).forEach(button => {
      const isActive = button === active;
      button.classList.toggle('active', isActive);
      button.setAttribute('aria-selected', String(isActive));
    });
  }

  function reportTargetColumns() {
    const columns = [
      { label: '대상', render: row => targetCell(row), className: 'nowrap-cell' },
      { label: '신고', render: row => `<strong class="report-count">${formatNumber(row.reportCount)}건</strong><small>최근 ${escapeHtml(shortDate(row.lastReportedAt).slice(5, 16))}</small>`, className: 'nowrap-cell' },
      { label: '내용', render: row => contentPreviewCell(row.content), className: 'content-cell' },
      { label: '작성자', render: row => userCell(row.content?.authorId, row.content?.authorNickname), className: 'user-id-cell' },
      { label: '콘텐츠 상태', render: row => row.content?.exists ? pill(contentStatusLabel(row.content.status), statusTone(row.content.status)) : pill('없음', 'danger') },
    ];
    if (reportStatus !== 'pending') {
      columns.push({ label: '처리', render: row => pill(RESOLUTION_LABEL[row.resolution] || '—', 'neutral') });
      columns.push({ label: '처리 시각', render: row => dateCell(row.lastProcessedAt), className: 'nowrap-cell' });
    }
    columns.push({ label: '', render: row => reportTargetActions(row), className: 'cell-actions report-actions' });
    return columns;
  }

  function reportTargetActions(row) {
    const data = { type: row.targetType, id: row.targetId };
    const buttons = [actionButton('report-detail', '신고 보기', data)];
    if (reportStatus === 'pending' && row.content?.exists) {
      const status = row.content.status;
      const decisionButton = (key, tone = '') => actionButton('report-process', DECISIONS[key].label, { ...data, decision: key }, tone, DECISION_HINTS[key]);
      if (status === 'published') buttons.push(decisionButton('hide', 'warn'));
      if (status !== 'deleted') buttons.push(decisionButton('delete', 'danger'));
      buttons.push(decisionButton('accept'));
      buttons.push(decisionButton('dismiss'));
      if (status === 'hidden') buttons.push(decisionButton('restore'));
    }
    return rowActions(buttons);
  }

  function targetCell(row) {
    const label = `${TARGET_LABEL[row.targetType] || row.targetType} #${row.targetId}`;
    const detail = row.targetType === 'comment' && row.content?.postId ? `게시글 #${row.content.postId}` : '';
    return strongWithSmall(label, detail);
  }

  function contentPreviewCell(content) {
    if (!content?.exists) return '<span class="muted">삭제된 대상</span>';
    const title = content.title ? `<strong class="cell-title">${escapeHtml(content.title)}</strong>` : '';
    return `${title}<span class="preview-text">${escapeHtml(content.preview || '')}</span>`;
  }

  function contentColumns() {
    const config = CONTENT_LISTS[contentList];
    if (config.kind === 'post') {
      return [
        { label: '게시글', render: row => strongWithSmall(row.title || '제목 없음', `#${row.id}`), className: 'content-cell' },
        { label: '작성자', render: row => userCell(row.authorId, row.authorNickname), className: 'user-id-cell' },
        { label: '상태', render: row => pill(contentStatusLabel(row.status), statusTone(row.status)) },
        { label: '좋아요 / 댓글', render: row => `${formatNumber(row.likeCount)} / ${formatNumber(row.commentCount)}`, className: 'nowrap-cell' },
        { label: '작성', render: row => dateCell(row.createdAt), className: 'nowrap-cell' },
        { label: '', render: row => contentActions('post', row.id, config.actions), className: 'cell-actions' },
      ];
    }
    return [
      { label: '댓글', render: row => strongWithSmall(previewText(row.content), `#${row.id} · 게시글 #${row.postId}`), className: 'content-cell' },
      { label: '작성자', render: row => userCell(row.authorId, row.authorNickname), className: 'user-id-cell' },
      { label: '상태', render: row => pill(contentStatusLabel(row.status), statusTone(row.status)) },
      { label: '좋아요', render: row => formatNumber(row.likeCount), className: 'nowrap-cell' },
      { label: '작성', render: row => dateCell(row.createdAt), className: 'nowrap-cell' },
      { label: '', render: row => contentActions('comment', row.id, config.actions), className: 'cell-actions' },
    ];
  }

  function contentActions(kind, id, actions) {
    const data = { kind, id };
    return rowActions([
      actionButton('content-view', '보기', data),
      ...actions.map(action => actionButton('content-action', CONTENT_ACTIONS[action].label, { ...data, op: action }, action === 'delete' ? 'danger' : '')),
    ]);
  }

  async function onActionClick(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;
    const { action } = button.dataset;
    if (action === 'report-detail') {
      openReportDetail(button.dataset.type, Number(button.dataset.id));
    } else if (action === 'report-process') {
      await processReport(button, button.dataset.type, Number(button.dataset.id), button.dataset.decision);
    } else if (action === 'content-view') {
      await lookup(button.dataset.kind, button.dataset.id, button);
    } else if (action === 'content-action') {
      await moderate(button, button.dataset.kind, button.dataset.id, button.dataset.op);
    }
  }

  function openReportDetail(targetType, targetId) {
    selectedTarget = { targetType, targetId };
    document.getElementById('report-detail-title').textContent = `${TARGET_LABEL[targetType] || targetType} #${targetId} 개별 신고`;
    const card = document.getElementById('report-detail-card');
    card.classList.remove('hidden');
    reportDetail.loaded = false;
    reportDetail.reload();
    card.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  function closeReportDetail() {
    selectedTarget = null;
    document.getElementById('report-detail-card').classList.add('hidden');
  }

  async function processReport(button, targetType, targetId, decisionKey) {
    const decision = DECISIONS[decisionKey];
    const row = reportTargets.rows.find(item => item.targetType === targetType && item.targetId === targetId);
    if (!decision || !row) return;
    await runConfirmed(button, {
      title: decision.title,
      message: '이 대상에 쌓인 검수 대기 신고가 모두 함께 처리됩니다.',
      rows: [
        ['대상', `${TARGET_LABEL[targetType]} #${targetId}`],
        ['내용', row.content?.title || row.content?.preview],
        ['작성자', row.content?.authorId ? `${row.content.authorNickname || 'user-' + row.content.authorId} (#${row.content.authorId})` : null],
        ['대기 신고', `${formatNumber(row.reportCount)}건`],
        ['현재 상태', contentStatusLabel(row.content?.status)],
        ['처리', decision.decision === 'resolve' ? '신고 인정' : '신고 기각'],
        ['콘텐츠 조치', RESOLUTION_LABEL[decision.contentAction]],
      ],
      confirmLabel: decision.confirmLabel,
      danger: decision.danger,
    }, () => postJson(`${API}/community/report-targets/${targetType}/${targetId}/process`, {
      decision: decision.decision,
      contentAction: decision.contentAction,
    }), {
      successMessage: `신고를 ${decision.decision === 'resolve' ? '인정' : '기각'}했습니다.`,
      after: refreshAfterChange,
    });
  }

  async function moderate(button, kind, id, op) {
    const config = CONTENT_ACTIONS[op];
    if (!config) return;
    const path = `${API}/community/${kind === 'comment' ? 'comments' : 'posts'}/${id}/${op}`;
    await runConfirmed(button, {
      title: `${TARGET_LABEL[kind]} #${id}을 ${config.verb}`,
      message: op === 'delete' ? '삭제한 콘텐츠는 목록에서 사라지고 복구 API가 없습니다.' : '',
      rows: [['대상', `${TARGET_LABEL[kind]} #${id}`], ['조치', config.label]],
      confirmLabel: config.confirmLabel,
      danger: config.danger,
    }, () => postJson(path, null), {
      successMessage: `${TARGET_LABEL[kind]} #${id}을 ${config.label} 처리했습니다.`,
      after: async () => {
        await refreshAfterChange();
        const lookupId = document.getElementById('community-lookup-id').value;
        if (lookupId === String(id) && document.getElementById('community-lookup-kind').value === kind) {
          await lookup(kind, id, null, { silent: true });
        }
      },
    });
  }

  async function refreshAfterChange() {
    await Promise.all([
      reportTargets.reload({ silent: true }),
      contents.reload({ silent: true }),
      selectedTarget ? reportDetail.reload({ silent: true }) : Promise.resolve(),
      typeof refreshOverview === 'function' ? refreshOverview({ silent: true }) : Promise.resolve(),
    ]);
  }

  async function lookup(kind, id, button, { silent = false } = {}) {
    if (!positiveIntOrNull(id)) {
      setStatus('조회할 ID를 입력하세요.', 'error');
      return;
    }
    document.getElementById('community-lookup-kind').value = kind;
    document.getElementById('community-lookup-id').value = id;
    await withBusy(button, '조회 중', async () => {
      try {
        const item = await getJson(`${API}/community/${kind === 'comment' ? 'comments' : 'posts'}/${id}`);
        renderDetail(item, kind);
        if (!silent) {
          document.getElementById('community-detail').scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        }
      } catch (err) {
        renderDetail(null, kind);
        if (!silent) setStatus(err.message, 'error');
      }
    });
  }

  function renderDetail(item, kind) {
    const target = document.getElementById('community-detail');
    const actions = document.getElementById('community-detail-actions');
    if (!item) {
      target.innerHTML = '<div class="empty-state">조회된 콘텐츠가 없습니다.</div>';
      actions.classList.add('hidden');
      actions.innerHTML = '';
      return;
    }
    const rows = kind === 'comment'
      ? [
          ['댓글', `#${item.id}`, 'mono'], ['게시글', `#${item.postId}`, 'mono'],
          ['작성자', userCell(item.authorId, item.authorNickname), 'html'],
          ['상태', pill(contentStatusLabel(item.status), statusTone(item.status)), 'html'],
          ['좋아요', formatNumber(item.likeCount)], ['작성', shortDate(item.createdAt), 'mono'],
          ['내용', item.content, 'wide'],
        ]
      : [
          ['게시글', `#${item.id}`, 'mono'],
          ['작성자', userCell(item.authorId, item.authorNickname), 'html'],
          ['상태', pill(contentStatusLabel(item.status), statusTone(item.status)), 'html'],
          ['좋아요 / 댓글', `${formatNumber(item.likeCount)} / ${formatNumber(item.commentCount)}`],
          ['작성', shortDate(item.createdAt), 'mono'],
          ['제목', item.title, 'wide'], ['본문', item.body, 'wide'],
        ];
    target.innerHTML = rows.map(([label, value, modifier]) => {
      const body = modifier === 'html' ? value : escapeHtml(isEmptyValue(value) ? '—' : value);
      return `<article class="detail-item ${modifier && modifier !== 'html' ? modifier : ''}"><strong>${escapeHtml(label)}</strong><span>${body}</span></article>`;
    }).join('');
    const available = item.status === 'published' ? ['hide', 'delete'] : item.status === 'hidden' ? ['unhide', 'delete'] : [];
    actions.innerHTML = available
      .map(op => actionButton('content-action', CONTENT_ACTIONS[op].label, { kind, id: item.id, op }, op === 'delete' ? 'danger' : ''))
      .join('');
    actions.classList.toggle('hidden', available.length === 0);
  }

  function contentStatusLabel(status) {
    return { published: '공개', hidden: '숨김', deleted: '삭제' }[status] || status || '—';
  }

  function reportStatusLabel(status) {
    return { pending: '대기', resolved: '인정', dismissed: '기각' }[status] || status || '—';
  }

  function previewText(value) {
    const textValue = isEmptyValue(value) ? '' : String(value);
    return textValue.length > 40 ? `${textValue.slice(0, 40)}…` : (textValue || '내용 없음');
  }

  return { init, activate };
})();
