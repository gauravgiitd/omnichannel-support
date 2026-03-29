const STORAGE_KEYS = {
    ticketId: "omnichannel.ticketId"
};

const ticketFromUrl = new URLSearchParams(window.location.search).get("ticket");

const state = {
    view: "landing",
    user: null,
    tickets: [],
    customerTickets: [],
    contactMappings: [],
    selectedTicketId: ticketFromUrl || localStorage.getItem(STORAGE_KEYS.ticketId),
    currentTicket: null,
    messages: [],
    documents: [],
    events: []
};

document.addEventListener("DOMContentLoaded", async () => {
    state.view = document.body.dataset.view || "landing";
    if (state.view === "landing" || state.view === "login") {
        return;
    }

    setupTabs();
    bindControls();
    bindForms();
    await loadSession();
    syncUserIdentity();
    await refreshBoard();
});

function setupTabs() {
    const buttons = document.querySelectorAll(".tab-button");
    const panels = document.querySelectorAll(".tab-panel");
    if (!buttons.length) {
        return;
    }

    buttons.forEach((button) => {
        button.addEventListener("click", () => {
            buttons.forEach((item) => item.classList.remove("active"));
            panels.forEach((panel) => panel.classList.remove("active"));
            button.classList.add("active");
            const panel = document.querySelector(`[data-panel="${button.dataset.tab}"]`);
            if (panel) {
                panel.classList.add("active");
            }
        });
    });
}

function bindControls() {
    bindClick("refreshBoard", () => refreshBoard(state.selectedTicketId));
    bindClick("toggleStartSupport", toggleStartSupport);
    bindClick("refreshAdmin", refreshAdminDashboard);
    bindClick("resetContactMappingForm", resetContactMappingForm);
}

function bindForms() {
    bindSubmit("appForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const response = await api("/v1/tickets/me", {
            method: "POST",
            body: {
                issue_type: "policy",
                lob: "motor",
                claim_id: null,
                policy_id: "POL-2026-4421",
                priority: "MEDIUM",
                source_channel: "UI",
                initial_message_body: data.get("initialMessageBody"),
                sender_identifier: state.user.email,
                initial_message_metadata: { source: "customer_app" },
                initial_external_thread_ref: `ui-${Date.now()}`
            }
        });

        const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));
        for (const file of uploadedFiles) {
            await api(`/v1/tickets/${response.data.ticket_id}/documents`, {
                method: "POST",
                body: {
                    channel: "UI",
                    sender_type: "CUSTOMER",
                    sender_identifier: state.user.email,
                    file_url: file.file_token,
                    document_type: "customer_upload",
                    message_body: "Customer uploaded a document while opening the ticket.",
                    metadata: {
                        source: "customer_app_upload",
                        drive_file_id: driveFileIdFromToken(file.file_token),
                        file_name: file.file_name,
                        mime_type: file.mime_type
                    }
                }
            });
        }

        pushEvent("Customer created a new in-app ticket", `${response.data.ticket_id} was created from the app.`);
        await refreshBoard(response.data.ticket_id);
    });

    bindSubmit("replyForm", async (event) => {
        ensureTicketSelected();
        const data = new FormData(event.currentTarget);
        const body = data.get("body");
        const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));

        await api(`/v1/tickets/${state.selectedTicketId}/messages`, {
            method: "POST",
            body: {
                channel: state.currentTicket ? state.currentTicket.source_channel : "UI",
                sender_type: "AGENT",
                sender_identifier: state.user.email,
                body,
                attachment_urls: [],
                metadata: { source: "agent_workspace" }
            }
        });

        for (const file of uploadedFiles) {
            await api(`/v1/tickets/${state.selectedTicketId}/documents`, {
                method: "POST",
                body: {
                    channel: state.currentTicket ? state.currentTicket.source_channel : "UI",
                    sender_type: "AGENT",
                    sender_identifier: state.user.email,
                    file_url: file.file_token,
                    document_type: "agent_attachment",
                    message_body: "Agent attached a supporting document.",
                    metadata: {
                        source: "agent_workspace_upload",
                        drive_file_id: driveFileIdFromToken(file.file_token),
                        file_name: file.file_name,
                        mime_type: file.mime_type
                    }
                }
            });
        }

        const origin = state.currentTicket ? channelLabel(state.currentTicket.source_channel) : "APP";
        pushEvent("Agent responded", `The reply was delivered to ${origin} and recorded in the shared ticket thread.`);
        await refreshBoard(state.selectedTicketId);
    });

    bindSubmit("patchForm", async (event) => {
        ensureTicketSelected();
        const data = new FormData(event.currentTarget);
        const response = await api(`/v1/tickets/${state.selectedTicketId}`, {
            method: "PATCH",
            body: pruneEmpty({
                status: data.get("status")
            })
        });

        pushEvent("Ticket updated", `${response.data.ticket_id} is now ${response.data.status}.`);
        await refreshBoard(response.data.ticket_id);
    });

    bindSubmit("adminCleanupForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const response = await api("/v1/admin/cleanup", {
            method: "POST",
            body: {
                confirmation: data.get("confirmation")
            }
        });
        text(
            "adminCleanupResult",
            `Deleted ${response.data.deleted_tickets} tickets, ${response.data.deleted_documents} documents, ${response.data.deleted_messages} messages, ${response.data.deleted_contact_mappings} contact mappings, ${response.data.deleted_identity_links} identity links, ${response.data.deleted_drive_files} Drive files, and ${response.data.deleted_drive_folders} Drive folders.`
        );
        await refreshAdminDashboard();
    });

    bindSubmit("customerCleanupForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const response = await api("/v1/admin/cleanup/customer", {
            method: "POST",
            body: {
                customerId: data.get("customerId"),
                confirmation: data.get("confirmation")
            }
        });
        text(
            "customerCleanupResult",
            `Deleted ${response.data.deleted_tickets} tickets, ${response.data.deleted_documents} documents, ${response.data.deleted_messages} messages, ${response.data.deleted_contact_mappings} contact mappings, ${response.data.deleted_identity_links} identity links, ${response.data.deleted_drive_files} Drive files, and ${response.data.deleted_drive_folders} Drive folders for ${response.data.customer_id}.`
        );
        event.currentTarget.reset();
        await refreshAdminDashboard();
    });

    bindSubmit("contactMappingForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const mappingId = data.get("mappingId");
        const path = mappingId ? `/v1/admin/contact-mappings/${mappingId}` : "/v1/admin/contact-mappings";
        const method = mappingId ? "PUT" : "POST";
        const response = await api(path, {
            method,
            body: {
                email: data.get("email"),
                phone: data.get("phone")
            }
        });
        text("contactMappingResult", `Saved mapping for ${response.data.email}.`);
        resetContactMappingForm();
        await refreshAdminDashboard();
    });
}

async function refreshBoard(preferredTicketId) {
    if (state.view === "customer") {
        const customerResponse = await api("/v1/customers/me/tickets");
        state.customerTickets = customerResponse.data;
        state.tickets = customerResponse.data;
    } else {
        const response = await api("/v1/tickets");
        state.tickets = response.data;
    }

    renderMetrics();
    renderQueueBoard();
    renderCustomerTicketList();

    const ticketId = preferredTicketId || state.selectedTicketId || findRelevantTicketId();
    if (ticketId) {
        await selectTicket(ticketId);
    } else {
        clearWorkspace();
    }
}

async function refreshAdminDashboard() {
    if (state.view !== "admin") {
        return;
    }
    const [summaryResponse, mappingsResponse] = await Promise.all([
        api("/v1/admin/summary"),
        api("/v1/admin/contact-mappings")
    ]);
    state.contactMappings = mappingsResponse.data;
    renderAdminMetrics(summaryResponse.data);
    renderContactMappings();
}

function findRelevantTicketId() {
    const list = state.view === "customer" ? state.customerTickets : state.tickets;
    return list[0] ? list[0].ticket_id : null;
}

async function selectTicket(ticketId) {
    persistTicketId(ticketId);
    const [ticketResponse, messageResponse, documentResponse] = await Promise.all([
        api(`/v1/tickets/${ticketId}`),
        api(`/v1/tickets/${ticketId}/messages`),
        api(`/v1/tickets/${ticketId}/documents`)
    ]);

    state.currentTicket = ticketResponse.data;
    state.messages = messageResponse.data;
    state.documents = documentResponse.data;

    syncFormsWithTicket();
    renderAgentWorkspace();
    renderCustomerExperience();
    renderCustomerTicketList();
    renderQueueBoard();
}

function renderMetrics() {
    const metricsEl = el("metrics");
    if (!metricsEl) {
        return;
    }

    const metrics = [
        { label: "Total tickets", value: state.tickets.length, copy: "All omnichannel issues" },
        { label: "Open tickets", value: state.tickets.filter((ticket) => !["RESOLVED", "CLOSED"].includes(ticket.status)).length, copy: "Still active with support" },
        { label: "Customers", value: new Set(state.tickets.map((ticket) => ticket.customer_id)).size, copy: "Customers represented" }
    ];

    metricsEl.innerHTML = metrics.map((metric) => `
        <article class="metric-card">
            <span class="eyebrow">${metric.label}</span>
            <strong>${metric.value}</strong>
            <p>${metric.copy}</p>
        </article>
    `).join("");
}

function renderAdminMetrics(summary) {
    const metricsEl = el("adminMetrics");
    if (!metricsEl || !summary) {
        return;
    }
    const metrics = [
        { label: "Tickets", value: summary.tickets, copy: "Total tickets in database" },
        { label: "Documents", value: summary.documents, copy: "Stored ticket documents" },
        { label: "Messages", value: summary.messages, copy: "Conversation messages" },
        { label: "Contact mappings", value: summary.contact_mappings, copy: "Strict email to phone mappings" },
        { label: "Identity links", value: summary.identity_links, copy: "Customer identifiers" },
        { label: "Audit logs", value: summary.audit_logs, copy: "Recorded audit events" },
        { label: "Merges", value: summary.merges, copy: "Ticket merge mappings" }
    ];
    metricsEl.innerHTML = metrics.map((metric) => `
        <article class="metric-card">
            <span class="eyebrow">${metric.label}</span>
            <strong>${metric.value}</strong>
            <p>${metric.copy}</p>
        </article>
    `).join("");
}

function renderContactMappings() {
    const container = el("contactMappingList");
    if (!container) {
        return;
    }
    const mappings = state.contactMappings || [];
    if (!mappings.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No mappings yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = mappings.map((mapping) => `
        <article class="ticket-card mapping-card" data-mapping-id="${mapping.id}">
            <div class="bubble-meta">
                <strong>${escapeHtml(mapping.email)}</strong>
                <span class="badge">${escapeHtml(mapping.phone)}</span>
            </div>
            <p class="ticket-supporting">Updated ${formatDate(mapping.updated_at)}</p>
            <div class="actions-inline">
                <button type="button" class="ghost-button mapping-edit" data-mapping-id="${mapping.id}">Edit</button>
                <button type="button" class="ghost-button mapping-delete" data-mapping-id="${mapping.id}">Delete</button>
            </div>
        </article>
    `).join("");
    container.querySelectorAll(".mapping-edit").forEach((button) => {
        button.addEventListener("click", () => populateContactMappingForm(button.dataset.mappingId));
    });
    container.querySelectorAll(".mapping-delete").forEach((button) => {
        button.addEventListener("click", () => deleteContactMapping(button.dataset.mappingId).catch(handleError));
    });
}

function renderQueueBoard() {
    const queueBoard = el("queueBoard");
    if (!queueBoard) {
        return;
    }

    const groups = [
        { title: "Open tickets", className: "triage", matcher: (_queue, ticket) => !["RESOLVED", "CLOSED"].includes(ticket.status) }
    ];

    queueBoard.innerHTML = groups.map((group) => {
        const tickets = state.tickets.filter((ticket) => group.matcher(ticket.assigned_queue, ticket));
        return `
            <section class="queue-column ${group.className}">
                <h4>${group.title}</h4>
                <div class="queue-stack">
                    ${tickets.length ? tickets.map(renderTicketCard).join("") : `<div class="empty-state">No tickets here.</div>`}
                </div>
            </section>
        `;
    }).join("");

    queueBoard.querySelectorAll(".ticket-card").forEach((card) => {
        card.addEventListener("click", () => selectTicket(card.dataset.ticketId).catch(handleError));
    });
}

function renderTicketCard(ticket) {
    const active = state.selectedTicketId === ticket.ticket_id ? "active" : "";
    const missingData = !ticket.policy_id && !ticket.claim_id;

    return `
        <article class="ticket-card ${active}" data-ticket-id="${ticket.ticket_id}">
            <div class="bubble-meta">
                <strong>${ticket.ticket_id}</strong>
                <span class="status-pill ${missingData ? "warning" : "good"}">${missingData ? "missing data" : "ready"}</span>
            </div>
            <div class="bubble-meta">
                <span class="badge">${ticket.source_channel}</span>
                <span class="badge">${ticket.status}</span>
            </div>
            <p class="ticket-supporting">${ticket.customer_id} • ${ticket.issue_type || "unclassified"} • ${ticket.assigned_queue || "triage"}</p>
        </article>
    `;
}

function renderAgentWorkspace() {
    const heading = el("ticketHeading");
    if (!heading) {
        return;
    }
    if (!state.currentTicket) {
        clearWorkspace();
        return;
    }

    heading.textContent = `${state.currentTicket.ticket_id} • ${state.currentTicket.issue_type || "unclassified"}`;
    const meta = el("ticketMeta");
    if (meta) {
        meta.innerHTML = [
            badge(state.currentTicket.source_channel),
            badge(state.currentTicket.status),
            badge(state.currentTicket.assigned_queue || "triage"),
            state.currentTicket.policy_id ? badge(`Policy ${state.currentTicket.policy_id}`) : "",
            state.currentTicket.claim_id ? badge(`Claim ${state.currentTicket.claim_id}`) : ""
        ].join("");
    }

    text("timelineCount", `${state.messages.length} messages`);
    text("documentCount", `${state.documents.length} docs`);
    renderChatThread("messageTimeline", state.messages, false);
    renderDocuments();
}

function renderCustomerExperience() {
    const customerHeading = el("customerAppHeading");
    if (!customerHeading) {
        return;
    }
    if (!state.currentTicket) {
        customerHeading.textContent = "Your tickets";
        text("customerAppSubhead", "Click any ticket below to expand its full conversation and continue the thread.");
        text("customerStatusPill", "Ready");
        return;
    }

    customerHeading.textContent = `${state.currentTicket.ticket_id} selected`;
    text("customerAppSubhead", "The expanded ticket shows the same support conversation, including ticket emails and agent replies.");
    text("customerStatusPill", state.currentTicket.status);
    setStartSupportCollapsed(true);
}

function renderCustomerTicketList() {
    const container = el("customerTicketList");
    if (!container) {
        return;
    }
    const tickets = state.customerTickets || [];
    text("customerTicketCount", `${tickets.length} ticket${tickets.length === 1 ? "" : "s"}`);
    if (!tickets.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No tickets yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = tickets.map(renderCustomerTicketAccordion).join("");
    container.querySelectorAll(".customer-ticket-header").forEach((card) => {
        card.addEventListener("click", () => toggleCustomerTicket(card.dataset.ticketId).catch(handleError));
    });
    hydrateExpandedCustomerTicket(container);
}

function renderChatThread(containerId, messages, customerView) {
    const container = el(containerId);
    renderChatThreadInElement(container, messages, customerView);
}

function renderChatThreadInElement(container, messages, customerView) {
    if (!container) {
        return;
    }
    if (!messages.length) {
        container.className = `chat-thread ${customerView ? "customer-view" : "agent-view"} empty-state`;
        container.textContent = customerView
            ? "No support ticket loaded in the app yet."
            : "Select a ticket to load the full conversation.";
        return;
    }

    container.className = `chat-thread ${customerView ? "customer-view" : "agent-view"}`;
    container.innerHTML = "";
    messages.forEach((message) => container.appendChild(buildMessageNode(message, customerView)));
}

function renderCustomerTicketAccordion(ticket) {
    const active = state.selectedTicketId === ticket.ticket_id;
    const missingData = !ticket.policy_id && !ticket.claim_id;
    const statusTone = missingData ? "warning" : "good";
    return `
        <article class="ticket-card customer-ticket-card ${active ? "active expanded" : ""}" data-ticket-id="${ticket.ticket_id}">
            <button type="button" class="customer-ticket-header" data-ticket-id="${ticket.ticket_id}">
                <div class="customer-ticket-summary">
                    <div class="bubble-meta">
                        <strong>${ticket.ticket_id}</strong>
                        <span class="status-pill ${statusTone}">${missingData ? "missing data" : "ready"}</span>
                    </div>
                    <div class="bubble-meta">
                        <span class="badge">${ticket.source_channel}</span>
                        <span class="badge">${ticket.status}</span>
                        <span class="badge">${ticket.assigned_queue || "triage"}</span>
                    </div>
                    <p class="ticket-supporting">${ticket.issue_type || "unclassified"} • ${ticket.customer_id}</p>
                </div>
                <span class="customer-ticket-chevron">${active ? "Hide" : "Open"}</span>
            </button>
            ${active ? `
                <div class="customer-ticket-body" data-ticket-body="${ticket.ticket_id}">
                    <div class="customer-ticket-details">
                        <span class="badge">Customer ${ticket.customer_id}</span>
                        ${ticket.policy_id ? `<span class="badge">Policy ${escapeHtml(ticket.policy_id)}</span>` : ""}
                        ${ticket.claim_id ? `<span class="badge">Claim ${escapeHtml(ticket.claim_id)}</span>` : ""}
                    </div>
                    <div class="chat-thread customer-ticket-chat empty-state">Loading conversation...</div>
                    <form id="customerComposeForm" class="stack-form compact customer-compose-form">
                        <label>
                            Message
                            <textarea name="body" rows="4" required>I want to continue on this same support ticket.</textarea>
                        </label>
                        <label>
                            Attach documents
                            <input name="attachments" type="file" multiple>
                        </label>
                        <p class="small-note">For email responses, reply directly to the ticket email in your inbox.</p>
                        <button type="submit">Send update to this ticket</button>
                    </form>
                </div>
            ` : ""}
        </article>
    `;
}

function hydrateExpandedCustomerTicket(container) {
    if (!container || !state.currentTicket || state.selectedTicketId !== state.currentTicket.ticket_id) {
        return;
    }
    const body = container.querySelector(`[data-ticket-body="${state.currentTicket.ticket_id}"]`);
    if (!body) {
        return;
    }
    const chatContainer = body.querySelector(".customer-ticket-chat");
    renderChatThreadInElement(chatContainer, state.messages, true);
    bindDynamicCustomerComposeForm(body.querySelector("#customerComposeForm"));
}

function buildMessageNode(message, customerView) {
    const template = document.getElementById("messageTemplate");
    const node = template.content.firstElementChild.cloneNode(true);
    const senderClass = message.sender_type === "AGENT" ? "agent" : message.sender_type === "CUSTOMER" ? "customer" : "system";
    node.classList.add(senderClass);
    node.querySelector(".bubble-meta").innerHTML = [
        badge(channelLabel(message.channel)),
        badge(message.sender_type),
        `<span class="small-note">${escapeHtml(message.sender_identifier || "unknown")}</span>`,
        `<span class="small-note">${formatDate(message.created_at)}</span>`
    ].join("");
    node.querySelector(".bubble-body").textContent = message.body || "";

    const attachments = [
        ...(message.attachment_urls || []).map((url) => `<a class="document-link" href="${url}" target="_blank" rel="noreferrer">${customerView ? "Open file" : "Attachment"}</a>`),
        ...(message.attachment_ids || []).map((id) => `<span class="badge">${id}</span>`)
    ];
    node.querySelector(".bubble-attachments").innerHTML = attachments.join("");
    return node;
}

function renderDocuments() {
    const container = el("documentList");
    const template = document.getElementById("documentTemplate");
    if (!container || !template) {
        return;
    }
    if (!state.documents.length) {
        container.className = "document-list empty-state";
        container.textContent = "Documents shared from any channel appear here.";
        return;
    }

    container.className = "document-list";
    container.innerHTML = "";
    state.documents.forEach((documentItem) => {
        const node = template.content.firstElementChild.cloneNode(true);
        node.querySelector(".document-type").textContent = documentItem.document_type;
        node.querySelector(".document-meta").textContent =
            `${channelLabel(documentItem.source_channel)} • ${documentItem.policy_id || "No policy"} • ${documentItem.claim_id || "No claim"}`;
        const link = node.querySelector(".document-link");
        link.href = documentItem.file_url;
        container.appendChild(node);
    });
}

function syncFormsWithTicket() {
    if (!state.currentTicket) {
        return;
    }
    setFormValue("#patchForm [name='status']", state.currentTicket.status || "");
}

function clearWorkspace() {
    text("ticketHeading", "Select a ticket");
    html("ticketMeta", "");
    renderChatThread("messageTimeline", [], false);
    renderChatThread("customerChat", [], true);
    const documentList = el("documentList");
    if (documentList) {
        documentList.className = "document-list empty-state";
        documentList.textContent = "Documents shared from any channel appear here.";
    }
    state.currentTicket = null;
    state.messages = [];
    state.documents = [];
    persistTicketId(null);
    renderCustomerExperience();
    renderCustomerTicketList();
    setStartSupportCollapsed(false);
}

function toggleStartSupport() {
    const body = el("startSupportBody");
    if (!body) {
        return;
    }
    setStartSupportCollapsed(!body.classList.contains("collapsed"));
}

function setStartSupportCollapsed(collapsed) {
    const body = el("startSupportBody");
    const button = el("toggleStartSupport");
    if (!body || !button) {
        return;
    }
    body.classList.toggle("collapsed", collapsed);
    button.textContent = collapsed ? "Open new ticket" : "Hide new ticket form";
}

function pushEvent(title, copy) {
    const feed = el("eventFeed");
    if (!feed) {
        return;
    }

    state.events.unshift({ title, copy, at: formatDate(new Date().toISOString()) });
    state.events = state.events.slice(0, 8);

    feed.className = "event-feed";
    feed.innerHTML = state.events.map((item) => `
        <article class="event-card">
            <strong>${item.title}</strong>
            <p>${item.copy}</p>
            <span class="small-note">${item.at}</span>
        </article>
    `).join("");
}

async function api(path, options = {}) {
    const response = await fetch(path, {
        method: options.method || "GET",
        headers: { "Content-Type": "application/json" },
        body: options.body ? JSON.stringify(options.body) : undefined
    });

    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.result === "ERROR") {
        throw new Error(payload.message || `Request failed for ${path}`);
    }
    return payload;
}

async function uploadSelectedFiles(files) {
    const uploads = [];
    for (const file of files || []) {
        if (!(file instanceof File) || !file.size) {
            continue;
        }
        const formData = new FormData();
        formData.append("file", file);
        const response = await fetch("/v1/files/upload", {
            method: "POST",
            body: formData
        });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok || payload.result === "ERROR") {
            throw new Error(payload.message || `Upload failed for ${file.name}`);
        }
        uploads.push(payload.data);
    }
    return uploads;
}

function bindSubmit(id, handler) {
    const form = el(id);
    if (!form) {
        return;
    }
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        try {
            await handler(event);
        } catch (error) {
            handleError(error);
        }
    });
}

function bindClick(id, handler) {
    const target = el(id);
    if (!target) {
        return;
    }
    target.addEventListener("click", async () => {
        try {
            await handler();
        } catch (error) {
            handleError(error);
        }
    });
}

function resetContactMappingForm() {
    const form = el("contactMappingForm");
    if (!form) {
        return;
    }
    form.reset();
    const mappingId = form.querySelector("[name='mappingId']");
    if (mappingId) {
        mappingId.value = "";
    }
    text("contactMappingResult", "");
}

function populateContactMappingForm(mappingId) {
    const mapping = (state.contactMappings || []).find((item) => `${item.id}` === `${mappingId}`);
    const form = el("contactMappingForm");
    if (!mapping || !form) {
        return;
    }
    form.querySelector("[name='mappingId']").value = mapping.id;
    form.querySelector("[name='email']").value = mapping.email;
    form.querySelector("[name='phone']").value = mapping.phone;
    text("contactMappingResult", `Editing mapping for ${mapping.email}`);
}

async function deleteContactMapping(mappingId) {
    await api(`/v1/admin/contact-mappings/${mappingId}`, {
        method: "DELETE"
    });
    text("contactMappingResult", "Mapping deleted.");
    resetContactMappingForm();
    await refreshAdminDashboard();
}

function ensureTicketSelected() {
    if (!state.selectedTicketId) {
        throw new Error("Select a ticket first.");
    }
}

async function submitCustomerComposeForm(event) {
    ensureTicketSelected();
    const data = new FormData(event.currentTarget);
    const body = data.get("body");
    const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));

    if (uploadedFiles.length) {
        for (let index = 0; index < uploadedFiles.length; index += 1) {
            const file = uploadedFiles[index];
            await api(`/v1/tickets/${state.selectedTicketId}/documents`, {
                method: "POST",
                body: pruneEmpty({
                    channel: "UI",
                    sender_type: "CUSTOMER",
                    sender_identifier: state.user.email,
                    file_url: file.file_token,
                    document_type: "supporting_document",
                    message_body: index === 0 ? body : "Additional supporting document",
                    metadata: {
                        source: "customer_conversation_upload",
                        drive_file_id: driveFileIdFromToken(file.file_token),
                        file_name: file.file_name,
                        mime_type: file.mime_type
                    }
                })
            });
        }
    } else {
        await api(`/v1/tickets/${state.selectedTicketId}/messages`, {
            method: "POST",
            body: {
                channel: "UI",
                sender_type: "CUSTOMER",
                sender_identifier: state.user.email,
                body,
                attachment_urls: [],
                metadata: { source: "customer_conversation" }
            }
        });
    }

    pushEvent("Customer continued existing ticket", `A new customer update was added to ${state.selectedTicketId}.`);
    await refreshBoard(state.selectedTicketId);
}

function bindDynamicCustomerComposeForm(form) {
    if (!form || form.dataset.bound === "true") {
        return;
    }
    form.dataset.bound = "true";
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        try {
            await submitCustomerComposeForm(event);
        } catch (error) {
            handleError(error);
        }
    });
}

function handleError(error) {
    pushEvent("Action failed", error.message || "Something went wrong.");
}

function syncTicketUrl(ticketId) {
    if (state.view !== "customer" || !window.history || !window.location) {
        return;
    }
    const url = new URL(window.location.href);
    if (ticketId) {
        url.searchParams.set("ticket", ticketId);
    } else {
        url.searchParams.delete("ticket");
    }
    window.history.replaceState({}, "", url);
}

async function loadSession() {
    const response = await api("/v1/me");
    state.user = response.data;
}

function persistTicketId(ticketId) {
    state.selectedTicketId = ticketId;
    if (ticketId) {
        localStorage.setItem(STORAGE_KEYS.ticketId, ticketId);
    } else {
        localStorage.removeItem(STORAGE_KEYS.ticketId);
    }
    syncTicketUrl(ticketId);
}

async function toggleCustomerTicket(ticketId) {
    if (state.view === "customer" && state.selectedTicketId === ticketId) {
        clearWorkspace();
        return;
    }
    await selectTicket(ticketId);
}

function syncUserIdentity() {
    if (!state.user) {
        return;
    }
    text("customerIdentity", state.user.name || state.user.email);
    text("customerIdentityLabel", state.user.email);
    text("agentIdentity", state.user.agent
        ? `Signed in as ${state.user.name || state.user.email}`
        : state.user.email);
    text("adminIdentity", state.user.roles && state.user.roles.includes("ROLE_ADMIN")
        ? `Signed in as ${state.user.name || state.user.email}`
        : state.user.email);
}

function el(id) {
    return document.getElementById(id);
}

function text(id, value) {
    const target = el(id);
    if (target) {
        target.textContent = value;
    }
}

function html(id, value) {
    const target = el(id);
    if (target) {
        target.innerHTML = value;
    }
}

function setFormValue(selector, value) {
    const input = document.querySelector(selector);
    if (input) {
        input.value = value;
    }
}

function parseCsv(value) {
    return `${value || ""}`.split(",").map((item) => item.trim()).filter(Boolean);
}

function pruneEmpty(payload) {
    return Object.fromEntries(Object.entries(payload).filter(([, value]) => value !== null && value !== ""));
}

function blankOrNull(value) {
    const normalized = `${value || ""}`.trim();
    return normalized ? normalized : null;
}

function defaultCustomerSenderIdentifier() {
    return state.user ? state.user.email : "";
}

function channelLabel(channel) {
    return channel === "UI" ? "APP" : channel;
}

function badge(textValue) {
    return `<span class="badge">${escapeHtml(textValue)}</span>`;
}

function escapeHtml(textValue) {
    return `${textValue}`.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

function formatDate(value) {
    const normalizedValue = normalizeDateValue(value);
    return new Intl.DateTimeFormat("en-IN", {
        dateStyle: "medium",
        timeStyle: "short",
        timeZone: "Asia/Kolkata",
        hour12: true
    }).format(new Date(normalizedValue));
}

function normalizeDateValue(value) {
    if (typeof value === "number") {
        return value < 1_000_000_000_000 ? value * 1000 : value;
    }
    if (typeof value === "string" && /^\d+$/.test(value)) {
        const parsed = Number(value);
        return parsed < 1_000_000_000_000 ? parsed * 1000 : parsed;
    }
    return value;
}

function guessMimeType(url) {
    const lower = url.toLowerCase();
    if (lower.endsWith(".pdf")) {
        return "application/pdf";
    }
    if (lower.endsWith(".png")) {
        return "image/png";
    }
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
        return "image/jpeg";
    }
    return "application/octet-stream";
}

function driveFileIdFromToken(token) {
    return `${token || ""}`.startsWith("drive://") ? token.slice("drive://".length) : null;
}

function encodeDriveAttachmentToken(file) {
    const fileId = encodeURIComponent(driveFileIdFromToken(file.file_token) || "");
    const fileName = encodeURIComponent(file.file_name || "");
    const mimeType = encodeURIComponent(file.mime_type || "");
    return `drive://${fileId}?name=${fileName}&mime=${mimeType}`;
}
