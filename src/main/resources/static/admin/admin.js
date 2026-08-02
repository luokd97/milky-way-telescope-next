let csrf;
let connections = [];
let currentConfigText = "";
let configActionsBusy = true;
let savedWatchTerms = [];
let watchTermsDraft = [];
let watchTermsActionsBusy = true;
const configEditorMedia = window.matchMedia("(max-width: 540px)");

document.addEventListener("DOMContentLoaded", async () => {
  initializeConfigEditor();
  initializeWatchTermsEditor();
  document.getElementById("config-form").addEventListener("submit", saveConfig);
  document.getElementById("config-json").addEventListener("input", updateConfigActions);
  document.getElementById("discard-config").addEventListener("click", discardConfig);
  document.getElementById("connection-list").addEventListener("click", handleRuntimeAction);
  try {
    await loadCsrf();
    const results = await Promise.allSettled([loadConfig(), refreshConnections()]);
    if (results[0].status === "rejected") {
      showConfigMessage(results[0].reason.message, true);
    }
    if (results[1].status === "rejected") {
      showRuntimeMessage(results[1].reason.message, true);
    }
    window.setInterval(() => refreshConnections().catch(() => {}), 2000);
  } catch (error) {
    showConfigMessage(error.message, true);
    showRuntimeMessage(error.message, true);
  }
});

function attachCsrfToLogout() {
  const form = document.getElementById("logout-form");
  if (!form || !csrf) return;
  const input = form.querySelector("input[data-csrf-field]") || document.createElement("input");
  input.type = "hidden";
  input.name = csrf.parameterName;
  input.value = csrf.token;
  input.dataset.csrfField = "true";
  if (!input.parentElement) form.appendChild(input);
}

function initializeConfigEditor() {
  document.getElementById("unlock-config").addEventListener("click", () => {
    setConfigEditorLocked(false);
    document.getElementById("config-json").focus();
  });
  document.getElementById("config-json").addEventListener("blur", () => {
    if (configEditorMedia.matches) setConfigEditorLocked(true);
  });
  configEditorMedia.addEventListener("change", syncConfigEditorMode);
  syncConfigEditorMode();
}

function syncConfigEditorMode() {
  setConfigEditorLocked(configEditorMedia.matches);
}

function setConfigEditorLocked(locked) {
  document.querySelector(".config-editor").classList.toggle("is-locked", locked);
  document.getElementById("config-json").readOnly = locked;
}

function initializeWatchTermsEditor() {
  document.getElementById("watch-terms-form").addEventListener("submit", saveWatchTerms);
  document.getElementById("watch-terms-list").addEventListener("input", handleWatchTermInput);
  document.getElementById("watch-terms-list").addEventListener("click", handleWatchTermAction);
  document.getElementById("add-watch-term").addEventListener("click", addWatchTerm);
  document.getElementById("discard-watch-terms").addEventListener("click", discardWatchTerms);
  renderWatchTerms();
}

function handleWatchTermInput(event) {
  const input = event.target.closest("input[data-watch-term-index]");
  if (!input) return;
  watchTermsDraft[Number(input.dataset.watchTermIndex)] = input.value;
  updateWatchTermsActions();
}

function handleWatchTermAction(event) {
  const button = event.target.closest("button[data-watch-term-action]");
  if (!button || watchTermsActionsBusy) return;
  const index = Number(button.dataset.watchTermIndex);
  const action = button.dataset.watchTermAction;
  if (!Number.isInteger(index) || index < 0 || index >= watchTermsDraft.length) return;

  if (action === "remove") {
    watchTermsDraft.splice(index, 1);
  } else if (action === "move-up" && index > 0) {
    [watchTermsDraft[index - 1], watchTermsDraft[index]] =
      [watchTermsDraft[index], watchTermsDraft[index - 1]];
  } else if (action === "move-down" && index < watchTermsDraft.length - 1) {
    [watchTermsDraft[index], watchTermsDraft[index + 1]] =
      [watchTermsDraft[index + 1], watchTermsDraft[index]];
  } else {
    return;
  }

  renderWatchTerms();
  updateWatchTermsActions();
  const nextIndex = action === "move-up" ? index - 1 : action === "move-down" ? index + 1 : index;
  if (action !== "remove") {
    const nextInput = document.querySelector(`input[data-watch-term-index="${nextIndex}"]`);
    nextInput?.focus();
  }
}

function addWatchTerm() {
  if (watchTermsActionsBusy) return;
  watchTermsDraft.push("");
  renderWatchTerms();
  updateWatchTermsActions();
  const lastIndex = watchTermsDraft.length - 1;
  const input = document.querySelector(`input[data-watch-term-index="${lastIndex}"]`);
  input?.focus();
}

function discardWatchTerms() {
  watchTermsDraft = [...savedWatchTerms];
  renderWatchTerms();
  updateWatchTermsActions();
  showWatchTermsMessage("Unsaved watch term changes discarded.", false);
}

function renderWatchTerms() {
  const list = document.getElementById("watch-terms-list");
  const empty = document.getElementById("watch-terms-empty");
  const count = document.getElementById("watch-terms-count");
  if (!list || !empty || !count) return;

  list.innerHTML = watchTermsDraft.map((term, index) => `
    <div class="watch-term-row">
      <span class="watch-term-index" aria-hidden="true">${index + 1}</span>
      <input class="watch-term-input" type="text" value="${escapeHtml(term)}"
          data-watch-term-index="${index}" aria-label="Watch term ${index + 1}">
      <div class="watch-term-actions">
        <button class="button-secondary watch-term-move" type="button"
            data-watch-term-action="move-up" data-watch-term-index="${index}"
            data-boundary-disabled="${index === 0}" aria-label="Move watch term ${index + 1} up"
            title="Move up"${index === 0 ? " disabled" : ""}>↑</button>
        <button class="button-secondary watch-term-move" type="button"
            data-watch-term-action="move-down" data-watch-term-index="${index}"
            data-boundary-disabled="${index === watchTermsDraft.length - 1}"
            aria-label="Move watch term ${index + 1} down" title="Move down"${index === watchTermsDraft.length - 1 ? " disabled" : ""}>↓</button>
        <button class="button-danger watch-term-remove" type="button"
            data-watch-term-action="remove" data-watch-term-index="${index}"
            aria-label="Remove watch term ${index + 1}" title="Remove">Remove</button>
      </div>
    </div>`).join("");
  empty.hidden = watchTermsDraft.length > 0;
  count.textContent = String(watchTermsDraft.length);
  setWatchTermControlsDisabled(watchTermsActionsBusy);
}

function setWatchTermControlsDisabled(disabled) {
  document.querySelectorAll("#watch-terms-list input, #watch-terms-list button")
    .forEach(control => {
      control.disabled = disabled || control.dataset.boundaryDisabled === "true";
    });
  document.getElementById("add-watch-term").disabled = disabled;
}

function syncWatchTermsFromConfig(config) {
  const terms = Array.isArray(config?.dashboard?.inventoryWatchTerms)
    ? config.dashboard.inventoryWatchTerms.map(term => String(term))
    : [];
  savedWatchTerms = [...terms];
  watchTermsDraft = [...terms];
  renderWatchTerms();
  updateWatchTermsActions();
}

function normalizeWatchTerms(values) {
  const normalized = [];
  const seen = new Set();
  for (const value of values) {
    const term = String(value ?? "").trim();
    if (!term || seen.has(term)) continue;
    seen.add(term);
    normalized.push(term);
  }
  return normalized;
}

function updateWatchTermsActions() {
  const hasChanges = JSON.stringify(watchTermsDraft) !== JSON.stringify(savedWatchTerms);
  const save = document.getElementById("save-watch-terms");
  const discard = document.getElementById("discard-watch-terms");
  const status = document.getElementById("watch-terms-status");
  if (!save || !discard || !status) return;

  save.disabled = watchTermsActionsBusy || !hasChanges;
  discard.disabled = watchTermsActionsBusy || !hasChanges;
  status.textContent = hasChanges ? "Unsaved changes" : "Saved";
  status.classList.toggle("is-unsaved", hasChanges);
}

function setWatchTermsActionsBusy(busy) {
  watchTermsActionsBusy = busy;
  setWatchTermControlsDisabled(busy);
  updateWatchTermsActions();
}

async function saveWatchTerms(event) {
  event.preventDefault();
  setWatchTermsActionsBusy(true);
  try {
    await mutate("/api/admin/settings/dashboard", "PUT", {
      inventoryWatchTerms: normalizeWatchTerms(watchTermsDraft),
    });
    await loadConfig();
    showWatchTermsMessage("Inventory watch terms saved and applied.", false);
  } catch (error) {
    showWatchTermsMessage(error.message, true);
  } finally {
    setWatchTermsActionsBusy(false);
  }
}

async function loadConfig(message = null) {
  setConfigActionsBusy(true);
  setWatchTermsActionsBusy(true);
  try {
    const config = await fetchJson("/api/admin/config");
    currentConfigText = JSON.stringify(config, null, 2);
    document.getElementById("config-json").value = currentConfigText;
    syncWatchTermsFromConfig(config);
    if (message) showConfigMessage(message, false);
  } finally {
    setConfigActionsBusy(false);
    setWatchTermsActionsBusy(false);
  }
}

async function discardConfig() {
  try {
    await loadConfig("Unsaved changes discarded.");
  } catch (error) {
    showConfigMessage(error.message, true);
  }
}

function updateConfigActions() {
  const hasChanges = document.getElementById("config-json").value !== currentConfigText;
  document.getElementById("save-config").disabled = configActionsBusy || !hasChanges;
  document.getElementById("discard-config").disabled = configActionsBusy || !hasChanges;
}

function setConfigActionsBusy(busy) {
  configActionsBusy = busy;
  updateConfigActions();
}

async function saveConfig(event) {
  event.preventDefault();
  let config;
  try {
    config = parseConfigText();
  } catch (error) {
    showConfigMessage(error.message, true);
    return;
  }

  setConfigActionsBusy(true);
  setWatchTermsActionsBusy(true);
  try {
    const saved = await mutate("/api/admin/config", "PUT", config);
    currentConfigText = JSON.stringify(saved, null, 2);
    document.getElementById("config-json").value = currentConfigText;
    syncWatchTermsFromConfig(saved);
    showConfigMessage("Configuration saved and applied.", false);
    await refreshConnections();
  } catch (error) {
    showConfigMessage(error.message, true);
  } finally {
    setConfigActionsBusy(false);
    setWatchTermsActionsBusy(false);
  }
}

function parseConfigText() {
  const value = document.getElementById("config-json").value.trim();
  if (!value) {
    throw new Error("Configuration JSON cannot be empty");
  }
  try {
    return JSON.parse(value);
  } catch (error) {
    throw new Error(`Invalid JSON: ${error.message}`);
  }
}

async function refreshConnections() {
  connections = await fetchJson("/api/admin/connections");
  document.getElementById("connection-count").textContent = connections.length;
  const list = document.getElementById("connection-list");
  list.innerHTML = connections.length ? connections.map(connectionRow).join("") :
    `<p class="muted">No characters configured.</p>`;
}

function connectionRow(connection) {
  const canDisconnect = connection.status === "connected" || connection.status === "connecting";
  return `
    <article class="connection-row">
      <div class="connection-summary">
        <div class="connection-title">
          <strong><span class="connection-character-label">Character </span>${escapeHtml(connection.characterId)}</strong>
          <span class="status ${statusClass(connection.status)}">
            ${escapeHtml(connection.status)}
          </span>
        </div>
        <div class="button-row connection-actions">
          ${canDisconnect ? `
            <button class="button-danger" data-action="disconnect" data-id="${escapeHtml(connection.characterId)}">
              Disconnect
            </button>` : `
            <button class="button-secondary" data-action="reconnect" data-id="${escapeHtml(connection.characterId)}">
              ${connection.status === "yielded" ? "Resume now" : "Reconnect"}
            </button>`}
          ${connection.status === "yielded" ? `
            <button class="button-secondary" data-action="extend-yield" data-id="${escapeHtml(connection.characterId)}">
              Extend yield
            </button>` : ""}
        </div>
      </div>
      ${connection.error ? `<p class="notice error">${escapeHtml(connection.error)}</p>` : ""}
      ${connection.status === "yielded" ? `
        <p class="notice">
          Game opened elsewhere · ${connection.resumeAt
            ? `Resume ${escapeHtml(formatResume(connection.resumeAt))}`
            : "Manual resume required"}
        </p>` : ""}
    </article>`;
}

async function handleRuntimeAction(event) {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  const id = button.dataset.id;
  const action = button.dataset.action;
  try {
    if (action === "disconnect") {
      if (!window.confirm(
        `Disconnect Character ${id}? The connection configuration will be kept and can be reconnected later.`
      )) {
        return;
      }
      await mutate(`/api/admin/connections/${encodeURIComponent(id)}/disconnect`, "POST");
    } else if (action === "reconnect") {
      await mutate(`/api/admin/connections/${encodeURIComponent(id)}/reconnect`, "POST");
    } else if (action === "extend-yield") {
      await mutate(`/api/admin/connections/${encodeURIComponent(id)}/yield/extend`, "POST");
    }
    await refreshConnections();
    hideRuntimeMessage();
  } catch (error) {
    showRuntimeMessage(error.message, true);
  }
}

function formatResume(value) {
  if (!value) return "after manual resume";
  const remaining = Math.max(0, new Date(value).getTime() - Date.now());
  const minutes = Math.ceil(remaining / 60000);
  if (minutes < 60) return `in ${minutes} minute${minutes === 1 ? "" : "s"}`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return `in ${hours}h${rest ? ` ${rest}m` : ""}`;
}

function statusClass(status) {
  if (status === "connected") return "connected";
  if (status === "yielded") return "yielded";
  return "disconnected";
}

async function mutate(url, method, body) {
  let response = await sendMutation(url, method, body);
  if (response.status === 403) {
    csrf = null;
    await loadCsrf();
    response = await sendMutation(url, method, body);
  }
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const payload = await response.json();
      message = payload.message || payload.detail || payload.error || message;
    } catch {}
    throw new Error(message);
  }
  return response.status === 204 ? null : response.json().catch(() => null);
}

async function sendMutation(url, method, body) {
  await loadCsrf();
  return fetch(url, {
    method,
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
}

async function loadCsrf() {
  if (csrf) return csrf;
  csrf = await fetchJson("/api/security/csrf");
  attachCsrfToLogout();
  return csrf;
}

async function fetchJson(url) {
  const response = await fetch(url, { cache: "no-store" });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

function showConfigMessage(message, error) {
  const root = document.getElementById("config-message");
  root.textContent = message;
  root.className = `notice${error ? " error" : ""}`;
  root.hidden = false;
}

function showWatchTermsMessage(message, error) {
  const root = document.getElementById("watch-terms-message");
  root.textContent = message;
  root.className = `notice${error ? " error" : ""}`;
  root.hidden = false;
}

function showRuntimeMessage(message, error) {
  const root = document.getElementById("runtime-message");
  root.textContent = message;
  root.className = `notice${error ? " error" : ""}`;
  root.hidden = false;
}

function hideRuntimeMessage() {
  const root = document.getElementById("runtime-message");
  root.textContent = "";
  root.className = "notice";
  root.hidden = true;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
