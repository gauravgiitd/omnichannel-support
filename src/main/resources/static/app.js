const STORAGE_KEYS = {
    ticketId: "omnichannel.ticketId"
};

const state = {
    view: "landing",
    user: null,
    tickets: [],
    customerTickets: [],
    selectedTicketId: localStorage.getItem(STORAGE_KEYS.ticketId),
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
}

function bindForms() {
    bindSubmit("emailForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));

        const response = await api("/v1/inbound/email", {
            method: "POST",
            body: {
                from_address: state.user.email,
                to_address: "support@acko.com",
                subject: data.get("subject"),
                body_text: data.get("bodyText"),
                message_id: `email-${Date.now()}`,
                force_new_ticket: true,
                attachments: uploadedFiles.map((file) => ({
                    file_url: file.file_token,
                    file_name: file.file_name,
                    mime_type: file.mime_type,
                    document_type: "email_attachment"
                }))
            }
        });

        pushEvent("Customer started on email", `Email created or updated ${response.data.ticket_number}.`);
        await refreshBoard(response.data.ticket_number);
    });

    bindSubmit("whatsappForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));

        const response = await api("/v1/inbound/whatsapp", {
            method: "POST",
            body: {
                wa_message_id: `wa-${Date.now()}`,
                from_e164_phone: data.get("fromE164Phone"),
                body_text: data.get("bodyText"),
                force_new_ticket: true,
                attachment_urls: uploadedFiles.map(encodeDriveAttachmentToken)
            }
        });

        pushEvent("Customer continued on WhatsApp", `WhatsApp stayed on ${response.data.ticket_number}.`);
        await refreshBoard(response.data.ticket_number);
    });

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

        await api(`/v1/tickets/${state.selectedTicketId}/messages`, {
            method: "POST",
            body: {
                channel: data.get("channel"),
                sender_type: "AGENT",
                sender_identifier: data.get("senderIdentifier"),
                body: data.get("body"),
                attachment_urls: [],
                metadata: { source: "agent_workspace" }
            }
        });

        pushEvent("Agent responded", `The agent replied on ${data.get("channel")} while preserving the same thread.`);
        await refreshBoard(state.selectedTicketId);
    });

    bindSubmit("patchForm", async (event) => {
        ensureTicketSelected();
        const data = new FormData(event.currentTarget);
        const response = await api(`/v1/tickets/${state.selectedTicketId}`, {
            method: "PATCH",
            body: pruneEmpty({
                issue_type: data.get("issueType"),
                lob: data.get("lob"),
                policy_id: data.get("policyId"),
                claim_id: data.get("claimId"),
                assigned_queue: data.get("assignedQueue"),
                assigned_agent: data.get("assignedAgent"),
                status: data.get("status")
            })
        });

        pushEvent("Ticket rerouted", `${response.data.ticket_id} now sits in ${response.data.assigned_queue || "triage"}.`);
        await refreshBoard(response.data.ticket_id);
    });

    bindSubmit("documentForm", async (event) => {
        ensureTicketSelected();
        const data = new FormData(event.currentTarget);
        await api(`/v1/tickets/${state.selectedTicketId}/documents`, {
            method: "POST",
            body: pruneEmpty({
                channel: data.get("channel"),
                sender_type: data.get("senderType"),
                sender_identifier: data.get("senderIdentifier"),
                file_url: data.get("fileUrl"),
                document_type: data.get("documentType"),
                policy_id: data.get("policyId"),
                message_body: data.get("messageBody"),
                metadata: { source: "agent_workspace" }
            })
        });

        pushEvent("Document attached", "A new document was captured into the central ticket document layer.");
        await refreshBoard(state.selectedTicketId);
    });

    bindSubmit("customerComposeForm", async (event) => {
        ensureTicketSelected();
        const data = new FormData(event.currentTarget);
        const channel = data.get("channel");
        const senderIdentifier = data.get("senderIdentifier");
        const body = data.get("body");
        const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));

        if (channel === "WHATSAPP") {
            await api("/v1/inbound/whatsapp", {
                method: "POST",
                body: {
                    wa_message_id: `wa-${Date.now()}`,
                    from_e164_phone: senderIdentifier,
                    body_text: body,
                    ticket_number_hint: state.selectedTicketId,
                    attachment_urls: uploadedFiles.map(encodeDriveAttachmentToken)
                }
            });
        } else {
            if (uploadedFiles.length) {
                for (let index = 0; index < uploadedFiles.length; index += 1) {
                    const file = uploadedFiles[index];
                    await api(`/v1/tickets/${state.selectedTicketId}/documents`, {
                        method: "POST",
                        body: pruneEmpty({
                            channel,
                            sender_type: "CUSTOMER",
                            sender_identifier: senderIdentifier,
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
                        channel,
                        sender_type: "CUSTOMER",
                        sender_identifier: senderIdentifier,
                        body,
                        attachment_urls: [],
                        metadata: { source: "customer_conversation" }
                    }
                });
            }
        }

        pushEvent("Customer continued existing ticket", `A new customer update was added to ${state.selectedTicketId}.`);
        await refreshBoard(state.selectedTicketId);
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
        { label: "Triage queue", value: state.tickets.filter((ticket) => (ticket.assigned_queue || "").toLowerCase().includes("triage")).length, copy: "Needs more context" },
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

function renderQueueBoard() {
    const queueBoard = el("queueBoard");
    if (!queueBoard) {
        return;
    }

    const groups = [
        { title: "Triage", className: "triage", matcher: (queue) => !queue || queue.toLowerCase().includes("triage") },
        { title: "Claims", className: "claims", matcher: (queue) => queue && queue.toLowerCase().includes("claim") },
        { title: "Policy", className: "policy", matcher: (queue) => queue && queue.toLowerCase().includes("policy") },
        { title: "Billing and other", className: "billing", matcher: (queue) => queue && !queue.toLowerCase().includes("triage") && !queue.toLowerCase().includes("claim") && !queue.toLowerCase().includes("policy") }
    ];

    queueBoard.innerHTML = groups.map((group) => {
        const tickets = state.tickets.filter((ticket) => group.matcher(ticket.assigned_queue));
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
        customerHeading.textContent = "No ticket selected";
        renderChatThread("customerChat", [], true);
        return;
    }

    customerHeading.textContent = `${state.currentTicket.ticket_id} in the app`;
    text("customerAppSubhead", "The app shows the same support conversation, including messages that started on email or continued on WhatsApp.");
    text("customerStatusPill", state.currentTicket.status);
    toggleConversationActions(true);
    setStartSupportCollapsed(true);
    setFormValue("#customerComposeForm [name='senderIdentifier']", defaultCustomerSenderIdentifier());
    renderChatThread("customerChat", state.messages, true);
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
    container.innerHTML = tickets.map(renderTicketCard).join("");
    container.querySelectorAll(".ticket-card").forEach((card) => {
        card.addEventListener("click", () => selectTicket(card.dataset.ticketId).catch(handleError));
    });
}

function renderChatThread(containerId, messages, customerView) {
    const container = el(containerId);
    if (!container) {
        return;
    }
    if (!messages.length) {
        container.className = "chat-thread empty-state";
        container.textContent = customerView
            ? "No support ticket loaded in the app yet."
            : "Select a ticket to load the full conversation.";
        return;
    }

    container.className = "chat-thread";
    container.innerHTML = "";
    messages.forEach((message) => container.appendChild(buildMessageNode(message, customerView)));
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
    setFormValue("#documentForm [name='policyId']", state.currentTicket.policy_id || "");
    setFormValue("#patchForm [name='issueType']", state.currentTicket.issue_type || "");
    setFormValue("#patchForm [name='lob']", state.currentTicket.lob || "");
    setFormValue("#patchForm [name='policyId']", state.currentTicket.policy_id || "");
    setFormValue("#patchForm [name='claimId']", state.currentTicket.claim_id || "");
    setFormValue("#patchForm [name='assignedQueue']", state.currentTicket.assigned_queue || "");
    setFormValue("#patchForm [name='assignedAgent']", state.currentTicket.assigned_agent || "");
    setFormValue("#patchForm [name='status']", state.currentTicket.status || "");
    setFormValue("#customerComposeForm [name='senderIdentifier']", defaultCustomerSenderIdentifier());
    setFormValue("#replyForm [name='senderIdentifier']", state.user ? state.user.email : "");
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
    toggleConversationActions(false);
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

function toggleConversationActions(visible) {
    const panel = el("customerConversationActions");
    if (!panel) {
        return;
    }
    panel.classList.toggle("hidden", !visible);
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

function ensureTicketSelected() {
    if (!state.selectedTicketId) {
        throw new Error("Select a ticket first.");
    }
}

function handleError(error) {
    pushEvent("Action failed", error.message || "Something went wrong.");
}

async function loadSession() {
    const response = await api("/v1/me");
    state.user = response.data;
}

function persistTicketId(ticketId) {
    state.selectedTicketId = ticketId;
    localStorage.setItem(STORAGE_KEYS.ticketId, ticketId);
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
    setFormValue("#emailForm [name='fromAddress']", state.user.email);
    setFormValue("#customerComposeForm [name='senderIdentifier']", defaultCustomerSenderIdentifier());
    setFormValue("#replyForm [name='senderIdentifier']", state.user.email);
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
    return new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
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
