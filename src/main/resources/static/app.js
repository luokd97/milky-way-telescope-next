const grid = document.getElementById("character-grid");

document.addEventListener("DOMContentLoaded", () => {
  attachCsrfToLogout();
  refresh();
  window.setInterval(refresh, 1000);
});

async function refresh() {
  try {
    const response = await fetch("/api/dashboard", { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const dashboard = await response.json();
    render(dashboard);
  } catch (error) {
    grid.innerHTML = `<article class="empty-card">Dashboard unavailable: ${escapeHtml(error.message)}</article>`;
  }
}

function render(dashboard) {
  const characters = dashboard.characters || [];
  document.getElementById("character-count").textContent = characters.length;
  document.getElementById("connected-count").textContent =
    characters.filter(item => item.connection?.connected).length;
  document.getElementById("last-refresh").textContent =
    `Updated ${new Date(dashboard.generatedAt).toLocaleTimeString()}`;

  if (!characters.length) {
    grid.innerHTML = `
      <article class="empty-card">
        <h2>No connections</h2>
        <p>Add a character from the connection admin.</p>
        <a class="button-link" href="/admin">Open connections</a>
      </article>`;
    return;
  }

  grid.innerHTML = characters.map(characterCard).join("");
}

function characterCard(snapshot) {
  const connection = snapshot.connection || {};
  const character = snapshot.character || {};
  const messages = snapshot.recentMessages || [];
  const statusClass = connection.connected ? "connected" : "disconnected";
  return `
    <article class="character-card">
      <header>
        <div>
          <p class="eyebrow">Character ${escapeHtml(connection.characterId || "-")}</p>
          <h2>${escapeHtml(character.name || "Waiting for baseline")}</h2>
        </div>
        <span class="status ${statusClass}">${escapeHtml(connection.status || "idle")}</span>
      </header>
      <dl class="metrics">
        <div><dt>Messages</dt><dd>${formatNumber(connection.totalMessages)}</dd></div>
        <div><dt>Last message</dt><dd>${formatTime(connection.lastMessageAt)}</dd></div>
        <div><dt>Mode</dt><dd>${escapeHtml(character.gameMode || "-")}</dd></div>
      </dl>
      ${connection.error ? `<p class="notice error">${escapeHtml(connection.error)}</p>` : ""}
      <div class="message-list">
        ${messages.length ? messages.map(message => `
          <div class="message-row">
            <time>${formatTime(message.receivedAt)}</time>
            <strong>${escapeHtml(message.type)}</strong>
            <span>${escapeHtml(message.summary)}</span>
          </div>`).join("") : `<p class="muted">No messages received in this session.</p>`}
      </div>
    </article>`;
}

async function attachCsrfToLogout() {
  const form = document.getElementById("logout-form");
  try {
    const token = await fetchCsrf();
    const input = document.createElement("input");
    input.type = "hidden";
    input.name = token.parameterName;
    input.value = token.token;
    form.appendChild(input);
  } catch {
    form.hidden = true;
  }
}

async function fetchCsrf() {
  const response = await fetch("/api/security/csrf", { cache: "no-store" });
  if (!response.ok) throw new Error("Unable to load CSRF token");
  return response.json();
}

function formatTime(value) {
  return value ? new Date(value).toLocaleTimeString() : "-";
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString();
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
