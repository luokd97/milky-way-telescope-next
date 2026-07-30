let csrf;
let connections = [];
let currentConfigText = "";
let configActionsBusy = true;
const configEditorMedia = window.matchMedia("(max-width: 540px)");

document.addEventListener("DOMContentLoaded", async () => {
  initializeConfigEditor();
  document.getElementById("config-form").addEventListener("submit", saveConfig);
  document.getElementById("config-json").addEventListener("input", updateConfigActions);
  document.getElementById("discard-config").addEventListener("click", discardConfig);
  document.getElementById("connection-list").addEventListener("click", handleRuntimeAction);
  try {
    csrf = await fetchJson("/api/security/csrf");
    attachCsrfToLogout();
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
  const input = document.createElement("input");
  input.type = "hidden";
  input.name = csrf.parameterName;
  input.value = csrf.token;
  form.appendChild(input);
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

async function loadConfig(message = null) {
  setConfigActionsBusy(true);
  try {
    const config = await fetchJson("/api/admin/config");
    currentConfigText = JSON.stringify(config, null, 2);
    document.getElementById("config-json").value = currentConfigText;
    if (message) showConfigMessage(message, false);
  } finally {
    setConfigActionsBusy(false);
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
  try {
    const saved = await mutate("/api/admin/config", "PUT", config);
    currentConfigText = JSON.stringify(saved, null, 2);
    document.getElementById("config-json").value = currentConfigText;
    showConfigMessage("Configuration saved and applied.", false);
    await refreshConnections();
  } catch (error) {
    showConfigMessage(error.message, true);
  } finally {
    setConfigActionsBusy(false);
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
  const response = await fetch(url, {
    method,
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
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
