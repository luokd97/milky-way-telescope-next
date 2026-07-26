let csrf;
let connections = [];
let sectionOrder = [];
let savedSectionOrder = [];

const DASHBOARD_SECTIONS = [
  {
    id: "currentActivity",
    label: "Current Activity",
    description: "Current action, tasks, consumables, and battle status.",
  },
  {
    id: "inventoryHighlights",
    label: "Inventory Highlights",
    description: "Watched or highest-quantity inventory items.",
  },
  {
    id: "actionQueue",
    label: "Action Queue",
    description: "Current and upcoming queued actions.",
  },
  {
    id: "recentAlerts",
    label: "Recent Alerts",
    description: "Recent low-inventory events.",
  },
];
const DEFAULT_SECTION_ORDER = DASHBOARD_SECTIONS.map(section => section.id);

document.addEventListener("DOMContentLoaded", async () => {
  document.getElementById("connection-form").addEventListener("submit", saveConnection);
  document.getElementById("cancel-edit").addEventListener("click", resetForm);
  document.getElementById("connection-list").addEventListener("click", handleAction);
  document.getElementById("dashboard-order-form").addEventListener("submit", saveDashboardSettings);
  document.getElementById("section-order-list").addEventListener("click", moveDashboardSection);
  document.getElementById("restore-section-order").addEventListener("click", restoreSectionOrder);
  try {
    csrf = await fetchJson("/api/security/csrf");
    attachCsrfToLogout();
    const results = await Promise.allSettled([refreshConnections(), loadDashboardSettings()]);
    if (results[0].status === "rejected") {
      showMessage(results[0].reason.message, true);
    }
    if (results[1].status === "rejected") {
      showDashboardSettingsMessage(results[1].reason.message, true);
    }
    window.setInterval(() => refreshConnections().catch(() => {}), 2000);
  } catch (error) {
    showMessage(error.message, true);
    showDashboardSettingsMessage(error.message, true);
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

async function refreshConnections() {
  connections = await fetchJson("/api/admin/connections");
  document.getElementById("connection-count").textContent = connections.length;
  const list = document.getElementById("connection-list");
  list.innerHTML = connections.length ? connections.map(connectionRow).join("") :
    `<p class="muted">No characters configured.</p>`;
}

async function loadDashboardSettings() {
  const settings = await fetchJson("/api/admin/settings/dashboard");
  sectionOrder = normalizeSectionOrder(settings.sectionOrder);
  savedSectionOrder = [...sectionOrder];
  renderSectionOrder();
}

function renderSectionOrder() {
  const root = document.getElementById("section-order-list");
  root.innerHTML = sectionOrder.map((sectionId, index) => {
    const section = DASHBOARD_SECTIONS.find(candidate => candidate.id === sectionId);
    return `
      <li class="section-order-row" data-section="${escapeHtml(sectionId)}">
        <span class="section-order-position" aria-hidden="true">${index + 1}</span>
        <span class="section-order-copy">
          <strong>${escapeHtml(section.label)}</strong>
          <span>${escapeHtml(section.description)}</span>
        </span>
        <span class="section-order-controls">
          <button class="order-button" type="button" data-move="up"
              aria-label="Move ${escapeHtml(section.label)} up" ${index === 0 ? "disabled" : ""}>↑</button>
          <button class="order-button" type="button" data-move="down"
              aria-label="Move ${escapeHtml(section.label)} down"
              ${index === sectionOrder.length - 1 ? "disabled" : ""}>↓</button>
        </span>
      </li>`;
  }).join("");
  document.getElementById("save-section-order").disabled = arraysEqual(sectionOrder, savedSectionOrder);
}

function moveDashboardSection(event) {
  const button = event.target.closest("button[data-move]");
  if (!button) return;
  const row = button.closest("[data-section]");
  const from = sectionOrder.indexOf(row.dataset.section);
  const to = button.dataset.move === "up" ? from - 1 : from + 1;
  if (from < 0 || to < 0 || to >= sectionOrder.length) return;
  [sectionOrder[from], sectionOrder[to]] = [sectionOrder[to], sectionOrder[from]];
  renderSectionOrder();
  document.getElementById("dashboard-settings-message").hidden = true;
}

function restoreSectionOrder() {
  sectionOrder = [...DEFAULT_SECTION_ORDER];
  renderSectionOrder();
  document.getElementById("dashboard-settings-message").hidden = true;
}

async function saveDashboardSettings(event) {
  event.preventDefault();
  try {
    const settings = await mutate("/api/admin/settings/dashboard", "PUT", { sectionOrder });
    sectionOrder = normalizeSectionOrder(settings.sectionOrder);
    savedSectionOrder = [...sectionOrder];
    renderSectionOrder();
    showDashboardSettingsMessage("Dashboard order saved. Character cards will update on their next refresh.", false);
  } catch (error) {
    showDashboardSettingsMessage(error.message, true);
  }
}

function normalizeSectionOrder(order) {
  if (!Array.isArray(order)
      || order.length !== DEFAULT_SECTION_ORDER.length
      || new Set(order).size !== DEFAULT_SECTION_ORDER.length
      || order.some(section => !DEFAULT_SECTION_ORDER.includes(section))) {
    return [...DEFAULT_SECTION_ORDER];
  }
  return [...order];
}

function arraysEqual(first, second) {
  return first.length === second.length && first.every((value, index) => value === second[index]);
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
            Game opened elsewhere · ${connection.resumeAt
              ? `Resume ${escapeHtml(formatResume(connection.resumeAt))}`
              : "Manual resume required"}
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
    await refreshConnections();
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
    await refreshConnections();
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

function showDashboardSettingsMessage(message, error) {
  const root = document.getElementById("dashboard-settings-message");
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
