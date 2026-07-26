let csrf;
let connections = [];
let currentConfigText = "";

document.addEventListener("DOMContentLoaded", async () => {
  document.getElementById("config-form").addEventListener("submit", saveConfig);
  document.getElementById("format-config").addEventListener("click", formatConfig);
  document.getElementById("load-config").addEventListener("click", loadConfig);
  document.getElementById("reset-config").addEventListener("click", resetConfig);
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

async function loadConfig() {
  const config = await fetchJson("/api/admin/config");
  currentConfigText = JSON.stringify(config, null, 2);
  document.getElementById("config-json").value = currentConfigText;
  showConfigMessage("Configuration loaded.", false);
}

function formatConfig() {
  try {
    const config = parseConfigText();
    document.getElementById("config-json").value = JSON.stringify(config, null, 2);
    showConfigMessage("JSON formatted. Save configuration to apply it.", false);
  } catch (error) {
    showConfigMessage(error.message, true);
  }
}

function resetConfig() {
  document.getElementById("config-json").value = currentConfigText;
  showConfigMessage("Unsaved changes were reset.", false);
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

  try {
    const saved = await mutate("/api/admin/config", "PUT", config);
    currentConfigText = JSON.stringify(saved, null, 2);
    document.getElementById("config-json").value = currentConfigText;
    showConfigMessage("Configuration saved and applied.", false);
    await refreshConnections();
  } catch (error) {
    showConfigMessage(error.message, true);
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
      <div>
        <div class="connection-title">
          <strong>Character ${escapeHtml(connection.characterId)}</strong>
          <span class="status ${statusClass(connection.status)}">
            ${escapeHtml(connection.status)}
          </span>
        </div>
        ${connection.error ? `<p class="notice error">${escapeHtml(connection.error)}</p>` : ""}
        ${connection.status === "yielded" ? `
          <p class="notice">
            Game opened elsewhere · ${connection.resumeAt
              ? `Resume ${escapeHtml(formatResume(connection.resumeAt))}`
              : "Manual resume required"}
          </p>` : ""}
      </div>
      <div class="button-row">
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
    showRuntimeMessage("Runtime action accepted.", false);
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

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
