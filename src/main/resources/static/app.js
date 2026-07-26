const state = {
  dashboard: null,
  polling: false,
  diagnosticCharacterId: null,
};

const grid = document.getElementById("character-grid");
const ITEM_SPRITE_PATH = "/assets/mwi/items.svg";
const BATTLE_ACTION_PREFIX = "/actions/combat/";
const DRINK_WARNING_THRESHOLD = 288;
const FOOD_WARNING_THRESHOLD = 1440;
const ACTION_QUEUE_COLLAPSED_LIMIT = 5;
const DEFAULT_SECTION_ORDER = [
  "currentActivity",
  "inventoryHighlights",
  "actionQueue",
  "recentAlerts",
];

document.addEventListener("DOMContentLoaded", () => {
  grid.addEventListener("click", handleCardAction);
  grid.addEventListener("keydown", handleCardSummaryKeydown);
  const diagnosticsDialog = document.getElementById("diagnostics-dialog");
  const diagnosticsOpen = document.getElementById("diagnostics-open");
  const diagnosticsClose = document.getElementById("diagnostics-close");

  diagnosticsOpen.addEventListener("click", () => {
    if (!diagnosticsDialog.open) diagnosticsDialog.showModal();
  });
  diagnosticsClose.addEventListener("click", () => diagnosticsDialog.close());
  diagnosticsDialog.addEventListener("click", event => {
    if (event.target === diagnosticsDialog) diagnosticsDialog.close();
  });
  diagnosticsDialog.addEventListener("close", () => diagnosticsOpen.focus());

  document.getElementById("diagnostic-character").addEventListener("change", event => {
    state.diagnosticCharacterId = event.target.value || null;
    renderDiagnostics(state.dashboard);
  });
  refresh();
  window.setInterval(refresh, 1000);
});

async function refresh() {
  if (state.polling) return;
  state.polling = true;
  try {
    const response = await fetch("/api/dashboard", { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    state.dashboard = await response.json();
    render(state.dashboard);
  } catch (error) {
    renderUnavailable(error);
  } finally {
    state.polling = false;
  }
}

function render(dashboard) {
  const characters = dashboard?.characters || [];
  renderHeaderSummary(dashboard);
  renderCharacterCards(characters, dashboard?.settings?.sectionOrder);
  renderDiagnostics(dashboard);
}

function renderHeaderSummary(dashboard, refreshFailed = false) {
  const items = [];
  const characters = dashboard?.characters || [];
  let mobileStatus = "Waiting";
  let mobileStatusClass = "";
  let recentAlerts = 0;

  if (dashboard) {
    const total = characters.length;
    const online = characters.filter(item =>
      item.connection?.connected && item.dataStatus === "live").length;
    const yielded = characters.filter(item => item.connection?.status === "yielded").length;
    recentAlerts = characters.reduce(
      (sum, item) => sum + (item.recentEvents?.length || 0),
      0,
    );

    items.push({
      text: total ? `${total} character${total === 1 ? "" : "s"}` : "No characters configured",
    });
    if (total) {
      if (online === total) {
        items.push({ text: "All online" });
      } else if (yielded === total) {
        items.push({ text: "All yielded", className: "header-summary-warning" });
      } else {
        items.push({ text: `${online} online` });
        if (yielded) {
          items.push({
            text: `${yielded} yielded`,
            className: "header-summary-warning",
          });
        }
      }
    }
    if (!total) {
      mobileStatus = "No characters";
    } else if (online === total) {
      mobileStatus = `${total} online`;
    } else if (yielded === total) {
      mobileStatus = `${total} yielded`;
      mobileStatusClass = "header-summary-warning";
    } else {
      mobileStatus = `${online}/${total} online`;
      mobileStatusClass = yielded ? "header-summary-warning" : "";
    }
    if (recentAlerts) {
      items.push({
        text: `${recentAlerts} recent alert${recentAlerts === 1 ? "" : "s"}`,
        className: "header-summary-alert",
      });
    }
  }

  if (refreshFailed) {
    mobileStatus = "Refresh failed";
    mobileStatusClass = "header-summary-alert";
    items.push({
      text: dashboard?.generatedAt
        ? `Refresh failed · Last updated ${formatRelativeTime(dashboard.generatedAt)}`
        : "Refresh failed",
      className: "header-refresh refresh-error",
    });
  } else if (dashboard?.generatedAt) {
    items.push({
      text: `Updated ${formatRelativeTime(dashboard.generatedAt)}`,
      className: "header-refresh",
    });
  }

  setHtml(
    "monitor-summary",
    `${items.map(item => `
      <span class="header-summary-item${item.className ? ` ${item.className}` : ""}">
        ${escapeHtml(item.text)}
      </span>`).join("")}
      <span class="header-summary-mobile">
        <span class="header-summary-mobile-text${mobileStatusClass ? ` ${mobileStatusClass}` : ""}">
          ${escapeHtml(mobileStatus)}
        </span>
        ${recentAlerts ? `
          <span class="mobile-alert-count"
              aria-label="${recentAlerts} recent alert${recentAlerts === 1 ? "" : "s"}">
            ${recentAlerts}
          </span>` : ""}
      </span>`,
  );
}

function renderCharacterCards(characters, sectionOrder) {
  if (!characters.length) {
    if (!grid.querySelector(".empty-card")) {
      grid.replaceChildren(emptyStateCard());
    }
    return;
  }

  const existing = new Map(Array.from(grid.children)
    .filter(element => element.dataset?.characterId)
    .map(element => [element.dataset.characterId, element]));
  const seen = new Set();

  characters.forEach((snapshot, index) => {
    const id = characterId(snapshot, index);
    const card = existing.get(id) || createCharacterCard();
    updateCharacterCard(card, snapshot, id, sectionOrder);
    seen.add(id);
    const current = grid.children[index];
    if (current !== card) {
      grid.insertBefore(card, current || null);
    }
  });

  Array.from(grid.children).forEach(element => {
    const id = element.dataset?.characterId;
    if (!id || !seen.has(id)) {
      element.remove();
    }
  });
}

function emptyStateCard() {
  const card = document.createElement("article");
  card.className = "empty-card";
  card.innerHTML = `
    <p class="eyebrow">No connections</p>
    <h2>Add a character to begin observing.</h2>
    <p class="muted">Connections stay read-only and can be managed separately.</p>
    <a class="button-link" href="/settings">Open settings</a>`;
  return card;
}

function createCharacterCard() {
  const card = document.createElement("article");
  card.className = "character-card";
  card.dataset.detailsExpanded = "false";
  card.innerHTML = `
    <div class="character-card-intro" data-card-summary="true"
        role="button" tabindex="0" aria-expanded="false">
      <header class="character-card-header">
        <div class="character-identity">
          <div class="character-name-line">
            <h2 data-field="character-name"></h2>
            <span class="character-reference">
              <span data-field="character-id"></span>
            </span>
            <span class="mode-badge" data-field="character-mode" hidden></span>
          </div>
          <p class="character-meta muted" data-field="status-detail"></p>
        </div>
        <div class="status-stack">
          <span class="status" data-field="character-status"></span>
        </div>
      </header>
      <div data-field="notices"></div>
    </div>

    <div class="card-sections">
      <section class="card-subpanel activity-panel" data-dashboard-section="currentActivity">
        <div class="section-heading">
          <span>Current activity</span>
          <span class="section-meta" data-field="queued-count"></span>
        </div>
        <div class="activity-line">
          <strong data-field="action-label"></strong>
          <span class="count-chip" data-field="action-count" hidden></span>
        </div>
        <div class="task-line">
          <span data-field="task-count"></span>
          <span data-field="task-overflow"></span>
        </div>
        <div data-field="drink-slots" hidden></div>
        <div class="battle-block" data-field="battle" hidden>
          <div class="battle-line">
            <strong data-field="battle-title"></strong>
            <span data-field="battle-meta"></span>
          </div>
          <div data-field="battle-slots"></div>
        </div>
      </section>

      <section class="card-subpanel" data-dashboard-section="inventoryHighlights">
        <div class="section-heading">
          <span>Inventory highlights<span class="section-meta" data-field="inventory-count"></span></span>
        </div>
        <div class="inventory-list" data-field="inventory"></div>
      </section>

      <section class="card-subpanel" data-dashboard-section="actionQueue">
        <div class="section-heading">
          <span>Action queue<span class="section-meta" data-field="action-queue-count"></span></span>
          <span class="section-heading-actions">
            <button class="section-toggle" type="button"
                data-field="action-queue-toggle" data-card-action="toggle-action-queue"
                aria-expanded="false" hidden></button>
          </span>
        </div>
        <div class="table-wrap compact-table-wrap">
          <table class="action-table">
            <tbody data-field="action-queue"></tbody>
          </table>
        </div>
      </section>

      <section class="card-subpanel event-panel" data-dashboard-section="recentAlerts">
        <div class="section-heading">
          <span>Recent alerts</span>
          <span class="section-meta" data-field="event-count"></span>
        </div>
        <div class="event-list" data-field="events"></div>
      </section>
    </div>`;
  return card;
}

function updateCharacterCard(card, snapshot, id, sectionOrder) {
  const connection = snapshot.connection || {};
  const character = snapshot.character || {};
  const action = snapshot.currentAction;
  const actions = snapshot.actionQueue || [];
  const dataStatus = snapshot.dataStatus || "waiting";
  const field = name => card.querySelector(`[data-field="${name}"]`);

  card.dataset.characterId = id;
  const detailsId = `character-card-details-${String(id).replace(/[^a-zA-Z0-9_-]/g, "-")}`;
  const detailsRoot = card.querySelector(".card-sections");
  detailsRoot.id = detailsId;
  if (card.dataset.detailsExpanded !== "true") {
    card.dataset.detailsExpanded = "false";
  }
  const characterName = character.name || "Waiting for character data";
  setElementText(field("character-id"), `#${connection.characterId || id}`);
  setElementText(field("character-name"), characterName);
  updateCardSummaryState(card, detailsId, characterName);
  const mode = modeLabel(character.gameMode);
  const modeElement = field("character-mode");
  setElementText(modeElement, mode);
  modeElement.dataset.mobileLabel = mode === "Iron Cow" ? "IC" : mode;
  if (mode) {
    modeElement.setAttribute("aria-label", mode);
  } else {
    modeElement.removeAttribute("aria-label");
  }
  setHidden(modeElement, !mode);

  const status = characterStatus(connection, dataStatus, snapshot.dataUpdatedAt);
  setElementClass(field("character-status"), `status ${status.className}`);
  setElementText(field("character-status"), status.label);
  setElementText(field("status-detail"), status.detail);
  setHidden(field("status-detail"), !status.detail);
  setElementHtml(field("notices"), noticesHtml(connection));

  const activeQueued = actions.filter(item => item.done !== true && item.current !== true).length;
  setElementText(field("queued-count"), activeQueued ? `+${activeQueued} queued` : "");
  setElementText(field("action-label"), action?.label || action?.actionHrid || "No current action");
  const count = actionCount(action);
  setElementText(field("action-count"), count);
  setHidden(field("action-count"), !count || isBattleAction(action));

  updateTask(card, snapshot.task);
  updateDrinkSlots(card, action, snapshot.currentActionDrinkSlots || []);
  updateBattle(card, action, snapshot.battle || {});
  updateInventory(card, snapshot.inventoryHighlights || []);
  updateActionQueue(card, actions);
  updateEvents(card, snapshot.recentEvents || []);
  applySectionOrder(card, sectionOrder);
}

function applySectionOrder(card, requestedOrder) {
  const order = normalizeSectionOrder(requestedOrder);
  const root = card.querySelector(".card-sections");
  order.forEach((sectionId, index) => {
    const section = root.querySelector(`[data-dashboard-section="${sectionId}"]`);
    const current = root.children[index];
    if (section && current !== section) {
      root.insertBefore(section, current || null);
    }
  });
}

function normalizeSectionOrder(order) {
  if (!Array.isArray(order)
      || order.length !== DEFAULT_SECTION_ORDER.length
      || new Set(order).size !== DEFAULT_SECTION_ORDER.length
      || order.some(section => !DEFAULT_SECTION_ORDER.includes(section))) {
    return DEFAULT_SECTION_ORDER;
  }
  return order;
}

function handleCardAction(event) {
  const button = event.target.closest("button[data-card-action]");
  if (button) {
    const card = button.closest(".character-card");
    if (!card) return;

    if (button.dataset.cardAction !== "toggle-action-queue") return;

    const expanded = card.dataset.actionQueueExpanded === "true";
    card.dataset.actionQueueExpanded = String(!expanded);
    const characters = state.dashboard?.characters || [];
    const snapshot = characters.find((candidate, index) =>
      characterId(candidate, index) === card.dataset.characterId);
    updateActionQueue(card, snapshot?.actionQueue || []);
    return;
  }

  const summary = event.target.closest("[data-card-summary]");
  const card = summary?.closest(".character-card");
  if (!card || !isMobileViewport()) return;
  toggleCardDetails(card);
}

function handleCardSummaryKeydown(event) {
  if (event.key !== "Enter" && event.key !== " ") return;
  const summary = event.target.closest("[data-card-summary]");
  const card = summary?.closest(".character-card");
  if (!card || !isMobileViewport()) return;
  event.preventDefault();
  toggleCardDetails(card);
}

function toggleCardDetails(card) {
  setCardDetailsExpanded(card, card.dataset.detailsExpanded !== "true");
}

function setCardDetailsExpanded(card, expanded) {
  card.dataset.detailsExpanded = String(expanded);
  const summary = card.querySelector("[data-card-summary]");
  if (!summary) return;

  const name = card.querySelector("[data-field='character-name']")?.textContent || "character";
  summary.setAttribute("aria-expanded", String(expanded));
  summary.setAttribute(
    "aria-label",
    expanded ? `Collapse ${name} details` : `Show more ${name} details`,
  );
}

function updateCardSummaryState(card, detailsId, characterName) {
  const summary = card.querySelector("[data-card-summary]");
  if (!summary) return;
  const expanded = card.dataset.detailsExpanded === "true";
  summary.tabIndex = isMobileViewport() ? 0 : -1;
  summary.setAttribute("aria-controls", detailsId);
  summary.setAttribute("aria-expanded", String(expanded));
  summary.setAttribute(
    "aria-label",
    expanded ? `Collapse ${characterName} details` : `Show more ${characterName} details`,
  );
}

function isMobileViewport() {
  return window.matchMedia?.("(max-width: 640px)").matches === true;
}

function updateTask(card, task) {
  setElementText(
    card.querySelector("[data-field='task-count']"),
    task ? `Tasks ${formatNumber(task.currentCount)} / ${formatNumber(task.maxCount)}` : "Tasks —",
  );
  const overflow = card.querySelector("[data-field='task-overflow']");
  const hasOverflow = task?.overflowAt != null;
  setElementText(
    overflow,
    hasOverflow ? `Overflow ${formatDurationUntil(task.overflowAt)}` : "",
  );
  setHidden(overflow, !hasOverflow);
}

function updateDrinkSlots(card, action, slots) {
  const root = card.querySelector("[data-field='drink-slots']");
  const visible = Boolean(action) && !isBattleAction(action);
  setHidden(root, !visible);
  if (visible) {
    updateSlotGroups(root, [{
      key: "drinks",
      label: "Drinks",
      slots,
      threshold: DRINK_WARNING_THRESHOLD,
      showItems: true,
    }]);
  }
}

function updateBattle(card, action, battle) {
  const root = card.querySelector("[data-field='battle']");
  const visible = isBattleAction(action);
  setHidden(root, !visible);
  if (!visible) return;

  const title = root.querySelector("[data-field='battle-title']");
  const meta = root.querySelector("[data-field='battle-meta']");
  if (battle.battleId == null) {
    setElementText(title, "Waiting for battle");
    setElementHtml(meta, "");
  } else {
    setElementText(title, battle.active ? `Battle #${battle.battleId}` : `Last battle #${battle.battleId}`);
    const elapsed = elapsedMilliseconds(battle.combatStartTime);
    setElementHtml(meta, `
      <span class="battle-state ${battle.active ? "active" : "waiting"}">${battle.active ? "Active" : "Waiting"}</span>
      <span>${battle.active ? `EPH ${formatPerHour(battle.battleId, elapsed)} · ${formatElapsed(elapsed)}` : ""}</span>`);
  }

  updateSlotGroups(root.querySelector("[data-field='battle-slots']"), [
    {
      key: "food",
      label: "Food",
      slots: battle.foodConsumableCounts || [],
      threshold: FOOD_WARNING_THRESHOLD,
      showItems: false,
    },
    {
      key: "drinks",
      label: "Drinks",
      slots: battle.drinkConsumableCounts || [],
      threshold: DRINK_WARNING_THRESHOLD,
      showItems: false,
    },
  ]);
}

function updateInventory(card, items) {
  const root = card.querySelector("[data-field='inventory']");
  setElementText(card.querySelector("[data-field='inventory-count']"), `(${items.length})`);
  if (!items.length) {
    root.replaceChildren();
    return;
  }

  removeUnkeyedChildren(root);
  reconcileKeyedChildren(
    root,
    items,
    (item, index) => item.itemHash || `${item.itemHrid || "item"}:${item.enhancementLevel ?? 0}:${index}`,
    createInventoryItem,
    updateInventoryItem,
  );
}

function updateActionQueue(card, actions) {
  const visibleActions = actions.filter(action => action.done !== true);
  const root = card.querySelector("[data-field='action-queue']");
  const toggle = card.querySelector("[data-field='action-queue-toggle']");
  const canExpand = visibleActions.length > ACTION_QUEUE_COLLAPSED_LIMIT;
  if (!canExpand) {
    card.dataset.actionQueueExpanded = "false";
  }
  const expanded = canExpand && card.dataset.actionQueueExpanded === "true";
  const displayedActions = expanded
    ? visibleActions
    : visibleActions.slice(0, ACTION_QUEUE_COLLAPSED_LIMIT);

  setElementText(
    card.querySelector("[data-field='action-queue-count']"),
    `(${visibleActions.length})`,
  );
  setElementText(
    toggle,
    expanded ? "Show less" : `+${Math.max(0, visibleActions.length - ACTION_QUEUE_COLLAPSED_LIMIT)} more`,
  );
  setHidden(toggle, !canExpand);
  const expandedValue = String(expanded);
  if (toggle.getAttribute("aria-expanded") !== expandedValue) {
    toggle.setAttribute("aria-expanded", expandedValue);
  }
  const toggleLabel = expanded
    ? "Show fewer queued actions"
    : `Show ${Math.max(0, visibleActions.length - ACTION_QUEUE_COLLAPSED_LIMIT)} more queued actions`;
  if (toggle.getAttribute("aria-label") !== toggleLabel) {
    toggle.setAttribute("aria-label", toggleLabel);
  }

  if (!visibleActions.length) {
    root.replaceChildren();
    return;
  }

  removeUnkeyedChildren(root);
  reconcileKeyedChildren(
    root,
    displayedActions,
    (action, index) => action.ordinal ?? action.actionHrid ?? index,
    createActionRow,
    updateActionRow,
  );
}

function updateEvents(card, events) {
  const root = card.querySelector("[data-field='events']");
  const visibleEvents = events.slice(0, 8);
  setElementText(
    card.querySelector("[data-field='event-count']"),
    `${events.length} alert${events.length === 1 ? "" : "s"}`,
  );
  if (!visibleEvents.length) {
    root.replaceChildren();
    return;
  }

  removeUnkeyedChildren(root);
  reconcileKeyedChildren(
    root,
    visibleEvents,
    (event, index) => event.id ?? `${event.sourceMessageSequence ?? "event"}:${event.itemHrid ?? index}`,
    createEventRow,
    updateEventRow,
  );
}

function renderDiagnostics(dashboard) {
  const characters = dashboard?.characters || [];
  const select = document.getElementById("diagnostic-character");
  if (!characters.length) {
    setElementHtml(select, `<option value="">No characters</option>`);
    select.disabled = true;
    setText("diagnostic-count", "0 messages");
    setHtml("diagnostic-messages", `<tr><td colspan="5" class="empty-cell">No messages.</td></tr>`);
    return;
  }

  select.disabled = false;
  if (!state.diagnosticCharacterId
      || !characters.some((snapshot, index) => characterId(snapshot, index) === state.diagnosticCharacterId)) {
    state.diagnosticCharacterId = characterId(characters[0], 0);
  }
  setElementHtml(select, characters.map((snapshot, index) => {
    const id = characterId(snapshot, index);
    const name = snapshot.character?.name || `Character ${id}`;
    return `<option value="${escapeHtml(id)}">${escapeHtml(name)} · ${escapeHtml(id)}</option>`;
  }).join(""));
  select.value = state.diagnosticCharacterId;

  const selected = characters.find((snapshot, index) =>
    characterId(snapshot, index) === state.diagnosticCharacterId);
  const messages = selected?.recentMessages || [];
  const lastMessage = selected?.connection?.lastMessageAt
    ? ` · Last message ${formatRelativeTime(selected.connection.lastMessageAt)}`
    : "";
  setText(
    "diagnostic-count",
    `${messages.length} message${messages.length === 1 ? "" : "s"}${lastMessage}`,
  );
  setHtml(
    "diagnostic-messages",
    messages.length ? messages.map(message => `
      <tr>
        <td>${escapeHtml(message.sequence ?? "-")}</td>
        <td>${escapeHtml(formatTime(message.receivedAt))}</td>
        <td>${escapeHtml(formatNumber(message.byteLength))}</td>
        <td><code>${escapeHtml(message.type || "unknown")}</code></td>
        <td>${escapeHtml(message.summary || "")}</td>
      </tr>`).join("") : `<tr><td colspan="5" class="empty-cell">No messages in this session.</td></tr>`,
  );
}

function renderUnavailable(error) {
  if (!grid.querySelector(".error-card")) {
    const card = document.createElement("article");
    card.className = "empty-card error-card";
    grid.replaceChildren(card);
  }
  grid.querySelector(".error-card").innerHTML = `
    <p class="eyebrow">Dashboard unavailable</p>
    <h2>Unable to refresh monitor data.</h2>
    <p class="notice error">${escapeHtml(error.message)}</p>`;
  renderHeaderSummary(state.dashboard, true);
}

function updateSlotGroups(root, groups) {
  let list = root.querySelector(":scope > .slot-groups");
  if (!list) {
    list = document.createElement("div");
    list.className = "slot-groups";
    root.replaceChildren(list);
  }
  reconcileKeyedChildren(
    list,
    groups,
    group => group.key,
    createSlotGroup,
    updateSlotGroup,
  );
}

function createSlotGroup() {
  const group = document.createElement("div");
  group.className = "slot-group";

  const label = document.createElement("span");
  label.className = "slot-group-label";
  label.dataset.role = "label";

  const list = document.createElement("div");
  list.className = "slot-list";
  for (let index = 0; index < 3; index++) {
    list.appendChild(createSlotChip(index));
  }

  group.append(label, list);
  return group;
}

function updateSlotGroup(group, value) {
  group.dataset.slotGroup = value.key;
  setElementText(group.querySelector("[data-role='label']"), value.label);
  const chips = group.querySelector(".slot-list").children;
  for (let index = 0; index < 3; index++) {
    updateSlotChip(
      chips[index],
      value.slots?.[index] ?? null,
      index,
      value.threshold,
      value.showItems,
    );
  }
}

function createSlotChip(index) {
  const chip = document.createElement("span");
  chip.className = "slot-chip";
  chip.dataset.slotIndex = index;

  const icon = document.createElement("span");
  icon.className = "icon-anchor";
  icon.dataset.role = "icon";

  const count = document.createElement("strong");
  count.dataset.role = "count";

  chip.append(icon, count);
  return chip;
}

function updateSlotChip(chip, slot, index, threshold, showItems) {
  const rawCount = typeof slot === "number" ? slot : slot?.count;
  const count = Number(rawCount || 0);
  const hasItem = Boolean(slot?.itemHrid);
  const low = showItems ? hasItem && count < threshold : count < threshold;
  const label = slot?.label || slot?.itemHrid || `Slot ${index + 1}`;
  setElementClass(chip, `slot-chip${low ? " low" : ""}${!hasItem && showItems ? " empty" : ""}`);
  setElementTitle(chip, `${label}: ${formatItemCount(count)}`);
  updateIconAnchor(chip.querySelector("[data-role='icon']"), slot?.itemHrid, String(index + 1));
  setElementText(
    chip.querySelector("[data-role='count']"),
    hasItem || !showItems ? formatItemCount(count) : "—",
  );
}

function createInventoryItem() {
  const root = document.createElement("div");
  root.className = "inventory-item";

  const icon = document.createElement("span");
  icon.className = "icon-anchor";
  icon.dataset.role = "icon";

  const copy = document.createElement("span");
  copy.className = "inventory-item-copy";
  const label = document.createElement("span");
  label.dataset.role = "label";
  const count = document.createElement("strong");
  count.dataset.role = "count";
  copy.append(label, count);

  root.append(icon, copy);
  return root;
}

function updateInventoryItem(root, item) {
  const label = item.label || item.itemHrid || "Unknown item";
  const enhancement = Number(item.enhancementLevel) > 0 ? ` +${item.enhancementLevel}` : "";
  setElementTitle(root, `${label}${enhancement} × ${formatItemCount(item.count)}`);
  updateIconAnchor(root.querySelector("[data-role='icon']"), item.itemHrid, "·");
  setElementText(root.querySelector("[data-role='label']"), `${label}${enhancement}`);
  setElementText(root.querySelector("[data-role='count']"), formatItemCount(item.count));
}

function createActionRow() {
  const row = document.createElement("tr");
  const actionCell = document.createElement("td");
  const label = document.createElement("span");
  label.dataset.role = "label";
  actionCell.append(label);

  const count = document.createElement("td");
  count.dataset.role = "count";
  row.append(actionCell, count);
  return row;
}

function updateActionRow(row, action) {
  setElementClass(row, action.current ? "current-row" : "");
  setElementText(row.querySelector("[data-role='label']"), action.label || action.actionHrid || "Action");
  setElementText(row.querySelector("[data-role='count']"), actionCount(action) || "∞");
}

function createEventRow() {
  const row = document.createElement("div");
  row.className = "event-row";

  const item = document.createElement("span");
  item.className = "event-item";
  const icon = document.createElement("span");
  icon.className = "icon-anchor";
  icon.dataset.role = "icon";
  const copy = document.createElement("span");
  const label = document.createElement("strong");
  label.dataset.role = "label";
  const hint = document.createElement("small");
  hint.textContent = "Low inventory";
  copy.append(label, hint);
  item.append(icon, copy);

  const value = document.createElement("span");
  value.className = "event-value";
  value.dataset.role = "count";
  row.append(item, value);
  return row;
}

function updateEventRow(row, event) {
  const label = event.label || event.itemHrid || "Low item";
  updateIconAnchor(row.querySelector("[data-role='icon']"), event.itemHrid, "·");
  setElementText(row.querySelector("[data-role='label']"), label);
  setElementText(row.querySelector("[data-role='count']"), formatItemCount(event.count));
}

function updateIconAnchor(anchor, itemHrid, fallbackText) {
  const iconId = iconIdFromHrid(itemHrid);
  const iconKey = iconId ? `item:${iconId}` : `fallback:${fallbackText}`;
  if (anchor.dataset.iconKey === iconKey) return;

  if (iconId) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("class", "mwi-icon");
    svg.setAttribute("aria-hidden", "true");
    const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
    use.setAttribute("href", `${ITEM_SPRITE_PATH}#${iconId}`);
    svg.appendChild(use);
    anchor.replaceChildren(svg);
  } else {
    const fallback = document.createElement("span");
    fallback.className = fallbackText === "·" ? "item-icon-fallback" : "slot-index";
    fallback.setAttribute("aria-hidden", "true");
    fallback.textContent = fallbackText;
    anchor.replaceChildren(fallback);
  }
  anchor.dataset.iconKey = iconKey;
}

function reconcileKeyedChildren(root, items, keyOf, createElement, updateElement) {
  const existing = new Map(Array.from(root.children)
    .filter(element => element.dataset.renderKey)
    .map(element => [element.dataset.renderKey, element]));
  const seen = new Set();

  items.forEach((item, index) => {
    const key = String(keyOf(item, index));
    const element = existing.get(key) || createElement(item, index);
    element.dataset.renderKey = key;
    updateElement(element, item, index);
    seen.add(key);

    const current = root.children[index];
    if (current !== element) {
      root.insertBefore(element, current || null);
    }
  });

  Array.from(root.children).forEach(element => {
    if (!seen.has(element.dataset.renderKey)) {
      element.remove();
    }
  });
}

function removeUnkeyedChildren(root) {
  Array.from(root.children).forEach(element => {
    if (!element.dataset.renderKey) element.remove();
  });
}

function setElementTitle(element, value) {
  const title = String(value ?? "");
  if (element.title !== title) element.title = title;
}

function iconIdFromHrid(hrid) {
  const value = String(hrid || "");
  return value.substring(value.lastIndexOf("/") + 1).replace(/[^a-zA-Z0-9_-]/g, "");
}

function characterId(snapshot, index) {
  return String(snapshot?.connection?.characterId ?? snapshot?.character?.id ?? `character-${index}`);
}

function characterStatus(connection, dataStatus, dataUpdatedAt) {
  const connectionState = connection?.status || "idle";
  const hasSavedData = dataStatus === "live" || dataStatus === "stale";
  const lastUpdate = dataUpdatedAt ? `Updated ${formatRelativeTime(dataUpdatedAt)}` : "Update time unavailable";
  const cachedData = dataUpdatedAt ? `Cached · ${lastUpdate}` : "Cached";

  if (connectionState === "yielded") {
    return {
      className: "yielded",
      label: "Yielded",
      detail: hasSavedData ? cachedData : "",
    };
  }

  if (connection?.connected) {
    if (dataStatus === "live") {
      return { className: "online", label: "Online", detail: lastUpdate };
    }
    return {
      className: "syncing",
      label: "Syncing",
      detail: dataStatus === "stale"
        ? dataUpdatedAt
          ? cachedData
          : "Waiting for fresh data"
        : "Waiting for data",
    };
  }

  if (connectionState === "connecting") {
    return {
      className: "reconnecting",
      label: hasSavedData ? "Reconnecting" : "Connecting",
      detail: hasSavedData ? cachedData : "Waiting for data",
    };
  }

  return {
    className: "offline",
    label: "Offline",
    detail: hasSavedData ? cachedData : "No data yet",
  };
}

function noticesHtml(connection) {
  const notices = [];
  if (connection.error) {
    notices.push(`<p class="notice error">${escapeHtml(connection.error)}</p>`);
  }
  if (connection.status === "yielded") {
    const resume = connection.resumeAt
      ? `Resume ${formatResume(connection.resumeAt)}`
      : "Manual resume required";
    notices.push(`
      <p class="notice warning">
        Game opened elsewhere · ${escapeHtml(resume)}
      </p>`);
  }
  return notices.join("");
}

function isBattleAction(action) {
  const hrid = String(action?.actionHrid || "").toLowerCase();
  const label = String(action?.label || "").toLowerCase();
  return hrid.startsWith(BATTLE_ACTION_PREFIX) || label === "battle" || label === "combat";
}

function actionCount(action) {
  if (!action) return "";
  const current = Number(action.currentCount);
  const maximum = Number(action.maxCount);
  if (Number.isFinite(maximum) && maximum > 0) {
    return `${formatNumber(current)} / ${formatNumber(maximum)}`;
  }
  return Number.isFinite(current) ? formatNumber(current) : "";
}

function modeLabel(mode) {
  const value = String(mode || "").trim();
  if (!value || value.toLowerCase() === "standard") return "";
  return value.toLowerCase() === "ironcow" ? "Iron Cow" : value;
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

function formatRelativeTime(value) {
  if (!value) return "—";
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000));
  if (seconds < 5) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  return formatTime(value);
}

function formatTime(value) {
  return value ? new Date(value).toLocaleTimeString() : "—";
}

function formatDurationUntil(value) {
  const milliseconds = new Date(value).getTime() - Date.now();
  if (!Number.isFinite(milliseconds)) return "—";
  if (milliseconds <= 0) return "now";
  const minutes = Math.ceil(milliseconds / 60000);
  if (minutes < 60) return `in ${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return `in ${hours}h${rest ? ` ${rest}m` : ""}`;
}

function elapsedMilliseconds(value) {
  if (!value) return 0;
  const elapsed = Date.now() - new Date(value).getTime();
  return Number.isFinite(elapsed) ? Math.max(0, elapsed) : 0;
}

function formatPerHour(count, elapsed) {
  if (!elapsed || elapsed < 1000) return "—";
  return (Number(count || 0) * 3600000 / elapsed).toLocaleString(undefined, {
    maximumFractionDigits: 1,
  });
}

function formatElapsed(milliseconds) {
  const totalSeconds = Math.floor(Math.max(0, milliseconds) / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours ? `${hours}h ${minutes}m` : minutes ? `${minutes}m ${seconds}s` : `${seconds}s`;
}

function formatNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString() : "0";
}

function formatItemCount(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "0";
  return number.toLocaleString(undefined, {
    maximumFractionDigits: Number.isInteger(number) ? 0 : 2,
  });
}

function setText(id, value) {
  setElementText(document.getElementById(id), value);
}

function setHtml(id, value) {
  setElementHtml(document.getElementById(id), value);
}

function setElementText(element, value) {
  const text = String(value ?? "");
  if (element.textContent !== text) element.textContent = text;
}

function setElementHtml(element, value) {
  const html = String(value ?? "");
  if (element.innerHTML !== html) element.innerHTML = html;
}

function setElementClass(element, value) {
  if (element.className !== value) element.className = value;
}

function setHidden(element, hidden) {
  if (element.hidden !== hidden) element.hidden = hidden;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
