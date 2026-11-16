const BASE = (() => {
  const path = window.location.pathname
    .replace(/\/index\.html$/, '')
    .replace(/\/login\.html$/, '')
    .replace(/\/+$/, '');
  return path === '/' ? '' : path;
})();

document.addEventListener('DOMContentLoaded', async () => {
  bindLoginEvents();
  try {
    const status = unwrap(await fetchJson(BASE + '/api/auth/status'));
    if (status.authenticated) {
      window.location.href = BASE + '/index.html';
      return;
    }
    if (status.isLocked) show('locked-section');
    else if (status.passkeyEnabled && status.hasPasskey && !status.devPasswordBypassEnabled) show('passkey-section');
    else show('password-section');
  } catch (_) {
    show('password-section');
  }
});

function bindLoginEvents() {
  document.getElementById('password-section').addEventListener('submit', event => {
    event.preventDefault();
    passwordLogin();
  });
  document.getElementById('passkey-login').addEventListener('click', passkeyLogin);
  document.getElementById('register-passkey').addEventListener('click', registerPasskey);
}

function show(id) {
  ['password-section', 'passkey-section', 'register-section', 'locked-section']
    .forEach(section => document.getElementById(section).classList.add('hidden'));
  document.getElementById(id).classList.remove('hidden');
}

async function passwordLogin() {
  const error = document.getElementById('login-error');
  const button = document.getElementById('password-login');
  error.classList.add('hidden');
  await withBusy(button, '확인 중', async () => {
    try {
      const response = await fetch(BASE + '/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: document.getElementById('username').value,
          password: document.getElementById('password').value,
        }),
      });
      if (response.ok) {
        const body = unwrap(await safeJson(response));
        if (body.adminSession) {
          window.location.href = BASE + '/index.html';
        } else {
          show('register-section');
        }
        return;
      }
      if (response.status === 423) {
        show('locked-section');
        return;
      }
      const body = await safeJson(response);
      error.textContent = body.error?.reason || '로그인 실패';
      error.classList.remove('hidden');
    } catch (err) {
      error.textContent = '서버 연결 실패';
      error.classList.remove('hidden');
    }
  });
}

async function registerPasskey() {
  const error = document.getElementById('register-error');
  const button = document.getElementById('register-passkey');
  error.classList.add('hidden');
  await withBusy(button, '등록 중', async () => {
    try {
      const data = unwrap(await fetchJson(BASE + '/api/auth/webauthn/register/options', { method: 'POST' }));
      const publicKey = data.options.publicKey;
      publicKey.challenge = base64UrlToBuffer(publicKey.challenge);
      publicKey.user.id = base64UrlToBuffer(publicKey.user.id);
      if (publicKey.excludeCredentials) {
        publicKey.excludeCredentials = publicKey.excludeCredentials.map(item => ({ ...item, id: base64UrlToBuffer(item.id) }));
      }
      const credential = await navigator.credentials.create({ publicKey });
      const finish = await fetch(BASE + '/api/auth/webauthn/register/finish', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          challengeId: data.challengeId,
          response: JSON.stringify({
            id: credential.id,
            rawId: bufferToBase64Url(credential.rawId),
            type: credential.type,
            response: {
              attestationObject: bufferToBase64Url(credential.response.attestationObject),
              clientDataJSON: bufferToBase64Url(credential.response.clientDataJSON),
            },
            clientExtensionResults: credential.getClientExtensionResults(),
          }),
        }),
      });
      if (finish.ok) window.location.href = BASE + '/index.html';
      else throw new Error('Passkey 등록 실패');
    } catch (err) {
      error.textContent = err.name === 'NotAllowedError' ? 'Passkey 등록이 취소되었습니다.' : err.message;
      error.classList.remove('hidden');
    }
  });
}

async function passkeyLogin() {
  const button = document.getElementById('passkey-login');
  await withBusy(button, '인증 중', async () => {
    try {
      const data = unwrap(await fetchJson(BASE + '/api/auth/webauthn/login/options', { method: 'POST' }));
      const publicKey = data.options.publicKey;
      publicKey.challenge = base64UrlToBuffer(publicKey.challenge);
      if (publicKey.allowCredentials) {
        publicKey.allowCredentials = publicKey.allowCredentials.map(item => ({ ...item, id: base64UrlToBuffer(item.id) }));
      }
      const assertion = await navigator.credentials.get({ publicKey });
      const finish = await fetch(BASE + '/api/auth/webauthn/login/finish', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          challengeId: data.challengeId,
          response: JSON.stringify({
            id: assertion.id,
            rawId: bufferToBase64Url(assertion.rawId),
            type: assertion.type,
            response: {
              authenticatorData: bufferToBase64Url(assertion.response.authenticatorData),
              clientDataJSON: bufferToBase64Url(assertion.response.clientDataJSON),
              signature: bufferToBase64Url(assertion.response.signature),
              userHandle: assertion.response.userHandle ? bufferToBase64Url(assertion.response.userHandle) : null,
            },
            clientExtensionResults: assertion.getClientExtensionResults(),
          }),
        }),
      });
      if (finish.ok) window.location.href = BASE + '/index.html';
      else alert('Passkey 인증 실패');
    } catch (err) {
      if (err.name !== 'NotAllowedError') alert('Passkey 인증 중 오류: ' + err.message);
    }
  });
}

async function fetchJson(url, options = {}) {
  const response = await fetch(url, options);
  return response.json();
}

async function safeJson(response) {
  try {
    return await response.json();
  } catch (_) {
    return {};
  }
}

async function withBusy(button, label, task) {
  if (button.disabled) return undefined;
  const original = button.textContent;
  button.disabled = true;
  button.textContent = label;
  try {
    return await task();
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

function unwrap(body) { return body.success || body; }

function base64UrlToBuffer(value) {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const pad = base64.length % 4 === 0 ? '' : '='.repeat(4 - (base64.length % 4));
  const binary = atob(base64 + pad);
  return Uint8Array.from(binary, ch => ch.charCodeAt(0)).buffer;
}

function bufferToBase64Url(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i += 1) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
