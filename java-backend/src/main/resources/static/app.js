// ============================================================
// Configuration
// ============================================================

const API = { base: '/api/v1' };
const $ = selector => document.querySelector(selector);

// One-time cleanup of tokens saved by the old implementation.
// No authentication tokens are read from or written to localStorage.
try {
  localStorage.removeItem('chatbot.accessToken');
  localStorage.removeItem('chatbot.accessTokenExpiresAt');
} catch (_) {
  // Authentication does not depend on localStorage.
}

// ============================================================
// Cookie authentication and CSRF
// ============================================================

let refreshInFlight = null;
let csrfInFlight = null;
let csrfToken = null;

function createHttpError(status, message) {
  const error = new Error(message || `HTTP ${status}`);
  error.status = status;
  return error;
}

async function responseMessage(response) {
  try {
    const body = await response.json();
    if (typeof body?.message === 'string') return body.message;
  } catch (_) {}
  return `HTTP ${response.status}`;
}

function invalidateCsrfToken() {
  csrfToken = null;
}

async function getCsrfToken() {
  if (csrfToken) return csrfToken;
  if (csrfInFlight) return csrfInFlight;

  csrfInFlight = (async () => {
    const response = await fetch(`${API.base}/csrf`, {
      method: 'GET',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' }
    });

    if (!response.ok) {
      throw createHttpError(
        response.status,
        await responseMessage(response)
      );
    }

    const envelope = await response.json();
    const token = envelope?.data?.token;

    if (
      envelope?.success !== true ||
      typeof token !== 'string' ||
      !token
    ) {
      throw new Error('Invalid CSRF endpoint response');
    }

    csrfToken = token;
    return token;
  })().finally(() => {
    csrfInFlight = null;
  });

  return csrfInFlight;
}

function isUnsafeMethod(method) {
  return !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(
    String(method || 'GET').toUpperCase()
  );
}

function isAuthEndpoint(path) {
  return path.startsWith('/auth/');
}

function invalidateAfterAuthTransition(path) {
  if (
    path === '/auth/login' ||
    path === '/auth/refresh' ||
    path === '/auth/logout'
  ) {
    invalidateCsrfToken();
  }
}

function goToLogin() {
  window.location.replace('/login.html');
}

// Refresh is performed using the HttpOnly refresh cookie.
// The backend must set new HttpOnly cookies and never return token values.
async function refreshAccessToken() {
  if (refreshInFlight) return refreshInFlight;

  refreshInFlight = (async () => {
    let token;

    try {
      token = await getCsrfToken();
    } catch (error) {
      console.error('Could not obtain CSRF token:', error);
      return false;
    }

    let response;

    try {
      response = await fetch(`${API.base}/auth/refresh`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': token
        }
      });
    } catch (error) {
      console.error('Refresh request failed:', error);
      return false;
    }

    if (!response.ok) {
      invalidateCsrfToken();
      return false;
    }

    if (response.status !== 204) {
      let envelope;

      try {
        envelope = await response.json();
      } catch (_) {
        return false;
      }

      if (envelope?.success !== true) return false;

      const data = envelope.data;

      // Detect a backend accidentally returning tokens in JSON.
      if (
        data &&
        typeof data === 'object' &&
        ('accessToken' in data || 'refreshToken' in data)
      ) {
        console.error(
          'Security error: authentication tokens must only be HttpOnly cookies.'
        );
        return false;
      }
    }

    invalidateCsrfToken();
    return true;
  })().finally(() => {
    refreshInFlight = null;
  });

  return refreshInFlight;
}

async function api(path, options = {}, alreadyRetried = false) {
  const method = String(options.method || 'GET').toUpperCase();

  const headers = {
    Accept: 'application/json',
    ...(options.headers || {})
  };

  if (options.body != null && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }

  // The browser sends matching HttpOnly cookies automatically.
  // CSRF protection remains required for unsafe methods.
  if (isUnsafeMethod(method)) {
    headers['X-XSRF-TOKEN'] = await getCsrfToken();
  }

  let response;

  try {
    response = await fetch(`${API.base}${path}`, {
      ...options,
      method,
      credentials: 'same-origin',
      headers
    });
  } catch (_) {
    throw createHttpError(0, 'Network request failed');
  }

  if (
    response.status === 401 &&
    !isAuthEndpoint(path) &&
    !alreadyRetried
  ) {
    const refreshed = await refreshAccessToken();

    if (refreshed) {
      return api(path, options, true);
    }

    goToLogin();
    throw new Error('unauthorized');
  }

  if (!response.ok) {
    if (response.status === 403) {
      invalidateCsrfToken();
    }

    throw createHttpError(
      response.status,
      await responseMessage(response)
    );
  }

  if (response.status === 204) {
    invalidateAfterAuthTransition(path);
    return null;
  }

  let envelope;

  try {
    envelope = await response.json();
  } catch (_) {
    throw new Error('The server returned invalid JSON');
  }

  if (envelope?.success === false) {
    throw createHttpError(
      response.status,
      envelope.message || 'Request failed'
    );
  }

  let data = envelope;

  if (
    envelope?.success === true &&
    Object.prototype.hasOwnProperty.call(envelope, 'data')
  ) {
    data = envelope.data;
  }

  // Login and refresh must never return authentication tokens in JSON.
  if (
    path === '/auth/login' ||
    path === '/auth/refresh'
  ) {
    if (
      data &&
      typeof data === 'object' &&
      ('accessToken' in data || 'refreshToken' in data)
    ) {
      throw new Error(
        'Backend error: tokens must be set as HttpOnly cookies.'
      );
    }
  }

  invalidateAfterAuthTransition(path);
  return data;
}

// ============================================================
// Chat-page state
// ============================================================

let current = null;
let busy = false;
let chatListRequestVersion = 0;
let chatViewVersion = 0;

// ============================================================
// Rendering
// ============================================================

function bubble(role, content) {
  const element = document.createElement('div');

  element.className =
    'msg ' + (role === 'user' ? 'user-m' : 'bot');

  element.textContent = content;

  $('#wrap').appendChild(element);
  $('#msgs').scrollTop = $('#msgs').scrollHeight;

  return element;
}

function showEmpty() {
  $('#wrap').innerHTML =
    '<div class="empty">' +
      '<h2>How can I help?</h2>' +
      '<p>Type a message to start.</p>' +
    '</div>';
}

function showChatNotice(message) {
  const notice = document.createElement('div');
  notice.className = 'msg bot';
  notice.setAttribute('role', 'alert');
  notice.textContent = message;

  $('#wrap').appendChild(notice);
  $('#msgs').scrollTop = $('#msgs').scrollHeight;

  return notice;
}

function showChatListStatus(list, message) {
  let status = list.querySelector('[data-chat-list-status]');

  if (!status) {
    status = document.createElement('div');
    status.className = 'item';
    status.dataset.chatListStatus = 'true';
    status.setAttribute('role', 'status');
    status.style.whiteSpace = 'normal';
    list.prepend(status);
  }

  status.textContent = message;
}

// ============================================================
// Load real chat history
// ============================================================

async function loadChats() {
  const requestVersion = ++chatListRequestVersion;
  const list = $('#list');

  try {
    const chats = await api('/chats');

    if (requestVersion !== chatListRequestVersion) return;

    if (!Array.isArray(chats)) {
      throw new Error('Invalid chat history response');
    }

    const fragment = document.createDocumentFragment();

    chats.forEach(chat => {
      const item = document.createElement('div');

      item.className =
        'item' + (chat.id === current ? ' on' : '');

      item.dataset.chatId = chat.id;
      item.textContent = chat.title;
      item.title = chat.title;
      item.setAttribute('role', 'button');
      item.tabIndex = 0;

      item.addEventListener('click', () => {
        void openChat(chat.id);
      });

      item.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          void openChat(chat.id);
        }
      });

      fragment.appendChild(item);
    });

    list.replaceChildren(fragment);
  } catch (error) {
    if (requestVersion !== chatListRequestVersion) return;
    if (error.message === 'unauthorized') return;

    console.error('Failed to load chats:', error);

    const hasPreviousChats =
      list.querySelectorAll('[data-chat-id]').length > 0;

    showChatListStatus(
      list,
      hasPreviousChats
        ? 'Could not refresh history. Previously loaded chats remain available.'
        : 'Could not load chat history. Please try again.'
    );
  }
}

// ============================================================
// Open a chat
// ============================================================

async function openChat(chatId) {
  const requestVersion = ++chatViewVersion;
  const wrap = $('#wrap');

  wrap.setAttribute('aria-busy', 'true');

  try {
    const messages = await api(
      `/chats/${encodeURIComponent(chatId)}/messages`
    );

    if (requestVersion !== chatViewVersion) return;

    if (!Array.isArray(messages)) {
      throw new Error('Invalid messages response');
    }

    current = chatId;
    $('#side').classList.remove('open');
    wrap.replaceChildren();

    if (messages.length) {
      messages.forEach(message => {
        bubble(message.role, message.content);
      });
    } else {
      showEmpty();
    }

    await loadChats();
  } catch (error) {
    if (requestVersion !== chatViewVersion) return;
    if (error.message === 'unauthorized') return;

    console.error('Failed to open chat:', error);

    showChatNotice(
      'Could not open that chat. The previous conversation ' +
      'is still displayed. Please try again.'
    );

    void loadChats();
  } finally {
    if (requestVersion === chatViewVersion) {
      wrap.removeAttribute('aria-busy');
    }
  }
}

// ============================================================
// New chat
// ============================================================

function newChat() {
  chatViewVersion++;
  current = null;

  showEmpty();
  $('#side').classList.remove('open');

  void loadChats();
  $('#input').focus();
}

// ============================================================
// Sending messages and handling errors
// ============================================================

function describeSendFailure(error) {
  if (error.status === 502) {
    return 'The AI service is unavailable. The backend did not save this message exchange. Please try again later.';
  }

  if ([400, 403, 404, 409, 413, 429].includes(error.status)) {
    return 'The request was rejected. The message was not accepted. Please check the request and try again.';
  }

  if (error.status >= 500 || !error.status) {
    return 'The request failed, and we could not confirm whether the message was saved. Reload the chat before retrying to avoid a possible duplicate.';
  }

  return 'The message could not be processed. Please try again.';
}

async function sendMessage(content) {
  if (busy) return false;

  busy = true;

  const input = $('#input');
  const sendButton = $('#form button[type="submit"]');

  if (sendButton) sendButton.disabled = true;

  try {
    return await sendNow(content);
  } catch (error) {
    if (error.message !== 'unauthorized') {
      console.error('Unexpected message-send failure:', error);

      showChatNotice(
        'Something went wrong while sending your message. ' +
        'Please check the connection and try again.'
      );
    }

    return false;
  } finally {
    busy = false;

    if (sendButton) sendButton.disabled = false;
    input.focus();
  }
}

async function sendNow(content) {
  const initialViewVersion = chatViewVersion;

  let targetChatId = current;
  let userBubble = null;
  let replyBubble = null;

  const isTargetVisible = () =>
    targetChatId !== null &&
    current === targetChatId &&
    chatViewVersion === initialViewVersion;

  try {
    if (!targetChatId) {
      const created = await api('/chats', {
        method: 'POST',
        body: JSON.stringify({})
      });

      if (!created?.id) {
        throw new Error('Chat creation returned no ID');
      }

      targetChatId = created.id;

      if (
        chatViewVersion === initialViewVersion &&
        current === null
      ) {
        current = targetChatId;
      }

      void loadChats();
    }

    if (isTargetVisible()) {
      if ($('#wrap .empty')) $('#wrap').replaceChildren();

      userBubble = bubble('user', content);
      replyBubble = bubble('assistant', 'Generating reply…');
    }

    try {
      const reply = await api(
        `/chats/${encodeURIComponent(targetChatId)}/messages`,
        {
          method: 'POST',
          body: JSON.stringify({ content })
        }
      );

      if (!reply || typeof reply.content !== 'string') {
        throw new Error('Invalid assistant response');
      }

      if (isTargetVisible() && replyBubble) {
        replyBubble.textContent = reply.content;
      }

      return true;
    } catch (error) {
      if (error.message === 'unauthorized') return false;

      console.error('Message request failed:', error);

      if (isTargetVisible() && replyBubble) {
        replyBubble.textContent = describeSendFailure(error);
        replyBubble.setAttribute('role', 'alert');

        if (userBubble) {
          userBubble.style.opacity = '0.75';
          userBubble.title =
            'The message may not exist in saved chat history.';
        }
      }

      return false;
    }
  } catch (error) {
    if (error.message === 'unauthorized') return false;

    console.error('Could not start chat:', error);

    if (
      chatViewVersion === initialViewVersion &&
      current === null
    ) {
      showChatNotice(
        'Could not create a chat. Your message was not sent. ' +
        'Please try again.'
      );
    } else if (isTargetVisible()) {
      showChatNotice('Could not send the message. Please try again.');
    }

    return false;
  } finally {
    await loadChats();
  }
}

// ============================================================
// Initialize chat page
// ============================================================

async function initChat() {
  try {
    const user = await api('/me');

    $('#who').textContent =
      user.email || user.name || 'Signed in';

    $('#newBtn').onclick = newChat;

    $('#menu').onclick = () => {
      $('#side').classList.toggle('open');
    };

    $('#logout').onclick = async () => {
      const button = $('#logout');
      button.disabled = true;

      try {
        await api('/auth/logout', { method: 'POST' });
      } catch (error) {
        console.error('Logout request failed:', error);
      } finally {
        invalidateCsrfToken();
        window.location.replace('/login.html');
      }
    };

    const input = $('#input');

    $('#form').onsubmit = event => {
      event.preventDefault();

      const content = input.value.trim();

      if (!content || busy) return;

      input.value = '';

      void sendMessage(content).then(sent => {
        if (!sent && !input.value) {
          input.value = content;
        }
      });
    };

    input.onkeydown = event => {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        $('#form').requestSubmit();
      }
    };

    showEmpty();
    await loadChats();
  } catch (error) {
    if (error.message === 'unauthorized') return;

    console.error('Chat initialization failed:', error);

    $('#wrap').replaceChildren();

    showChatNotice(
      'The chat page could not be initialized. ' +
      'Check your connection and reload the page.'
    );

    $('#input').disabled = true;

    const sendButton = $('#form button[type="submit"]');
    if (sendButton) sendButton.disabled = true;

    $('#newBtn').disabled = true;
  }
}