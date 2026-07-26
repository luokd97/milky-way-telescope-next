let csrf;
let connections = [];

document.addEventListener("DOMContentLoaded", async () => {
  document.getElementById("connection-form").addEventListener("submit", saveConnection);
  document.getElementById("cancel-edit").addEventListener("click", resetForm);
  document.getElementById("connection-list").addEventListener("click", handleAction);
  try {
    csrf = await fetchJson("/api/security/csrf");
    await refresh();
    window.setInterval(refresh, 2000);
  } catch (error) {
    showMessage(error.message, true);
  }
});

async function refresh() {
  connections = await fetchJson("/api/admin/connections");
  document.getElementById("connection-count").textContent = connections.length;
  const list = document.getElementById("connection-list");
  list.innerHTML = connections.length ? connections.map(connectionRow).join("") :
    `<p class="muted">No characters configured.</p>`;
}

function connectionRow(connection) {
  return `
    <article class="connection-row">
      <div>
        <div class="connection-title">
          <strong>Character ${escapeHtml(connection.characterId)}</strong>
          <span class="status ${statusClass(connection.status)}">
            ${escapeHtml(connection.status)}
          </span>
        </div>
        <code>${escapeHtml(connection.redactedUrl)}</code>
        ${connection.error ? `<p class="notice error">${escapeHtml(connection.error)}</p>` : ""}
        ${connection.status === "yielded" ? `
          <p class="notice">
            ${escapeHtml(connection.yieldReason || "Another game session was opened.")}
            Automatic resume ${escapeHtml(formatResume(connection.resumeAt))}.
          </p>` : ""}
      </div>
      <div class="button-row">
        <button class="button-secondary" data-action="edit" data-id="${escapeHtml(connection.characterId)}">Update</button>
        <button class="button-secondary" data-action="reconnect" data-id="${escapeHtml(connection.characterId)}">
          ${connection.status === "yielded" ? "Resume now" : "Reconnect"}
        </button>
        ${connection.status === "yielded" ? `
          <button class="button-secondary" data-action="extend-yield" data-id="${escapeHtml(connection.characterId)}">
            Extend yield
          </button>` : ""}
        <button class="button-danger" data-action="delete" data-id="${escapeHtml(connection.characterId)}">Delete</button>
      </div>
    </article>`;
}

async function saveConnection(event) {
  event.preventDefault();
  const editingId = document.getElementById("editing-id").value;
  const payload = {
    url: document.getElementById("url").value.trim(),
    accessToken: document.getElementById("access-token").value.trim(),
  };
  const endpoint = editingId
    ? `/api/admin/connections/${encodeURIComponent(editingId)}`
    : "/api/admin/connections";
  try {
    await mutate(endpoint, editingId ? "PUT" : "POST", payload);
    resetForm();
    await refresh();
  } catch (error) {
    showMessage(error.message, true);
  }
}

async function handleAction(event) {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  const id = button.dataset.id;
  const action = button.dataset.action;
  if (action === "edit") {
    document.getElementById("editing-id").value = id;
    document.getElementById("url").value = "";
    document.getElementById("url").placeholder = "Paste the complete replacement WSS URL";
    document.getElementById("access-token").value = "";
    document.getElementById("form-title").textContent = `Update character ${id}`;
    document.getElementById("cancel-edit").hidden = false;
    document.getElementById("url").focus();
    return;
  }
  if (action === "delete" && !window.confirm(`Delete character ${id}?`)) return;
  try {
    if (action === "reconnect") {
      await mutate(`/api/admin/connections/${encodeURIComponent(id)}/reconnect`, "POST");
    } else if (action === "extend-yield") {
      await mutate(`/api/admin/connections/${encodeURIComponent(id)}/yield/extend`, "POST");
    } else if (action === "delete") {
      await mutate(`/api/admin/connections/${encodeURIComponent(id)}`, "DELETE");
    }
    await refresh();
  } catch (error) {
    showMessage(error.message, true);
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

function resetForm() {
  document.getElementById("connection-form").reset();
  document.getElementById("editing-id").value = "";
  document.getElementById("form-title").textContent = "Add character";
  document.getElementById("cancel-edit").hidden = true;
  document.getElementById("form-message").hidden = true;
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
      message = payload.message || payload.detail || message;
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

function showMessage(message, error) {
  const root = document.getElementById("form-message");
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
