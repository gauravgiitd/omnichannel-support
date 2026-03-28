const STORAGE_KEYS = {
    customerId: "omnichannel.customerId",
    ticketId: "omnichannel.ticketId"
};

const state = {
    view: "landing",
    tickets: [],
    customerTickets: [],
    selectedTicketId: localStorage.getItem(STORAGE_KEYS.ticketId),
    currentTicket: null,
    messages: [],
    documents: [],
    events: [],
    customerId: localStorage.getItem(STORAGE_KEYS.customerId) || "CUST-DEMO-001"
};

document.addEventListener("DOMContentLoaded", () => {
    state.view = document.body.dataset.view || "landing";
    if (state.view === "landing") {
        return;
    }

    setupTabs();
    bindControls();
    bindForms();
    syncCustomerIdentity();
    refreshBoard();
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
    bindClick("scenarioUnifiedJourney", runUnifiedJourneyScenario);
    bindClick("scenarioMissingData", runMissingDataScenario);
    bindClick("toggleStartSupport", toggleStartSupport);
}

function bindForms() {
    bindSubmit("emailForm", async (event) => {
        const data = new FormData(event.currentTarget);
        setCustomerId(data.get("customerIdHint"));

        const response = await api("/v1/inbound/email", {
            method: "POST",
            body: {
                from_address: data.get("fromAddress"),
                to_address: "support@acko.com",
                subject: data.get("subject"),
                body_text: data.get("bodyText"),
                message_id: `email-${Date.now()}`,
                customer_id_hint: blankOrNull(data.get("customerIdHint")),
                policy_id_hint: blankOrNull(data.get("policyIdHint")),
                claim_id_hint: blankOrNull(data.get("claimIdHint")),
                force_new_ticket: true,
                attachments: parseCsv(data.get("attachments")).map((url, index) => ({
                    file_url: url,
                    file_name: `email-attachment-${index + 1}`,
                    mime_type: guessMimeType(url),
                    document_type: "email_attachment"
                }))
            }
        });

        pushEvent("Customer started on email", `Email created or updated ${response.data.ticket_number}.`);
        await refreshBoard(response.data.ticket_number);
    });

    bindSubmit("whatsappForm", async (event) => {
        const data = new FormData(event.currentTarget);
        setCustomerId(data.get("customerIdHint"));

        const response = await api("/v1/inbound/whatsapp", {
            method: "POST",
            body: {
                wa_message_id: `wa-${Date.now()}`,
                from_e164_phone: data.get("fromE164Phone"),
                body_text: data.get("bodyText"),
                customer_id_hint: blankOrNull(data.get("customerIdHint")),
                ticket_number_hint: blankOrNull(data.get("ticketNumberHint")),
                policy_id_hint: blankOrNull(data.get("policyIdHint")),
                force_new_ticket: true,
                attachment_urls: parseCsv(data.get("attachmentUrls"))
            }
        });

        pushEvent("Customer continued on WhatsApp", `WhatsApp stayed on ${response.data.ticket_number}.`);
        await refreshBoard(response.data.ticket_number);
    });

    bindSubmit("appForm", async (event) => {
        const data = new FormData(event.currentTarget);
        setCustomerId(data.get("customerId"));
        const response = await api("/v1/tickets", {
            method: "POST",
            body: {
                customer_id: data.get("customerId"),
                issue_type: "policy",
                lob: "motor",
                claim_id: null,
                policy_id: "POL-2026-4421",
                priority: "MEDIUM",
                source_channel: "UI",
                initial_message_body: data.get("initialMessageBody"),
                sender_identifier: data.get("customerId"),
                initial_message_metadata: { source: "customer_app" },
                initial_external_thread_ref: `ui-${Date.now()}`
            }
        });

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
        const fileUrl = blankOrNull(data.get("fileUrl"));
        const body = data.get("body");

        if (channel === "WHATSAPP") {
            await api("/v1/inbound/whatsapp", {
                method: "POST",
                body: {
                    wa_message_id: `wa-${Date.now()}`,
                    from_e164_phone: senderIdentifier,
                    body_text: body,
                    customer_id_hint: state.customerId,
                    ticket_number_hint: state.selectedTicketId,
                    attachment_urls: fileUrl ? [fileUrl] : []
                }
            });
        } else if (fileUrl) {
            await api(`/v1/tickets/${state.selectedTicketId}/documents`, {
                method: "POST",
                body: pruneEmpty({
                    channel,
                    sender_type: "CUSTOMER",
                    sender_identifier: senderIdentifier,
                    file_url: fileUrl,
                    document_type: "supporting_document",
                    message_body: body,
                    metadata: { source: "customer_conversation" }
                })
            });
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

        pushEvent("Customer continued existing ticket", `A new customer update was added to ${state.selectedTicketId}.`);
        await refreshBoard(state.selectedTicketId);
    });
}

async function refreshBoard(preferredTicketId) {
    if (state.view === "customer") {
        const customerResponse = await api(`/v1/customers/${state.customerId}/tickets`);
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
    const customerMatch = list.find((ticket) => ticket.customer_id === state.customerId);
    return customerMatch ? customerMatch.ticket_id : (state.tickets[0] ? state.tickets[0].ticket_id : null);
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
    setCustomerId(state.currentTicket.customer_id);

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
    setFormValue("#customerComposeForm [name='senderIdentifier']", state.currentTicket.customer_id);
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
    setFormValue("#whatsappForm [name='ticketNumberHint']", state.currentTicket.ticket_id);
    setFormValue("#appForm [name='ticketIdHint']", state.currentTicket.ticket_id);
    setFormValue("#documentForm [name='policyId']", state.currentTicket.policy_id || "");
    setFormValue("#patchForm [name='issueType']", state.currentTicket.issue_type || "");
    setFormValue("#patchForm [name='lob']", state.currentTicket.lob || "");
    setFormValue("#patchForm [name='policyId']", state.currentTicket.policy_id || "");
    setFormValue("#patchForm [name='claimId']", state.currentTicket.claim_id || "");
    setFormValue("#patchForm [name='assignedQueue']", state.currentTicket.assigned_queue || "");
    setFormValue("#patchForm [name='assignedAgent']", state.currentTicket.assigned_agent || "");
    setFormValue("#patchForm [name='status']", state.currentTicket.status || "");
    setFormValue("#customerComposeForm [name='senderIdentifier']", state.currentTicket.customer_id);
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

async function runUnifiedJourneyScenario() {
    try {
        setCustomerId("CUST-DEMO-001");

        const email = await api("/v1/inbound/email", {
            method: "POST",
            body: {
                from_address: "ria.mehta@example.com",
                to_address: "support@acko.com",
                subject: "My policy issue",
                body_text: "I am starting my support request over email for a policy endorsement.",
                message_id: `email-${Date.now()}`,
                customer_id_hint: "CUST-DEMO-001",
                policy_id_hint: "POL-2026-4421",
                attachments: [{
                    file_url: "https://files.example/policy-copy.pdf",
                    file_name: "policy-copy.pdf",
                    mime_type: "application/pdf",
                    document_type: "policy_copy"
                }]
            }
        });

        const ticketId = email.data.ticket_number;

        await api("/v1/inbound/whatsapp", {
            method: "POST",
            body: {
                wa_message_id: `wa-${Date.now()}`,
                from_e164_phone: "+919900001234",
                body_text: "Continuing the same issue here and sharing my RC document.",
                customer_id_hint: "CUST-DEMO-001",
                ticket_number_hint: ticketId,
                policy_id_hint: "POL-2026-4421",
                attachment_urls: ["https://files.example/vehicle-rc.pdf"]
            }
        });

        await api(`/v1/tickets/${ticketId}/messages`, {
            method: "POST",
            body: {
                channel: "EMAIL",
                sender_type: "AGENT",
                sender_identifier: "aditi.sharma@acko.com",
                body: "I can see both your email and WhatsApp updates on the same ticket. You can also follow this in the app.",
                attachment_urls: [],
                metadata: { source: "scenario" }
            }
        });

        await api(`/v1/tickets/${ticketId}/messages`, {
            method: "POST",
            body: {
                channel: "UI",
                sender_type: "CUSTOMER",
                sender_identifier: "CUST-DEMO-001",
                body: "I opened the app and can see the full thread. Please let me know the next step.",
                attachment_urls: [],
                metadata: { source: "scenario" }
            }
        });

        pushEvent("Unified journey complete", `${ticketId} now demonstrates email start, WhatsApp continuation, and full app visibility.`);
        await refreshBoard(ticketId);
    } catch (error) {
        handleError(error);
    }
}

async function runMissingDataScenario() {
    try {
        const response = await api("/v1/inbound/email", {
            method: "POST",
            body: {
                from_address: "missing.context@example.com",
                to_address: "support@acko.com",
                subject: "Need help",
                body_text: "There is an issue with my policy.",
                message_id: `email-${Date.now()}`,
                customer_id_hint: "CUST-DEMO-TRIAGE"
            }
        });

        const ticketId = response.data.ticket_number;
        await api(`/v1/tickets/${ticketId}`, {
            method: "PATCH",
            body: {
                issue_type: "policy",
                lob: "motor",
                policy_id: "POL-TRIAGE-445",
                status: "ASSIGNED"
            }
        });

        pushEvent("Iterative routing demo", `${ticketId} moved from triage after customer data arrived.`);
        await refreshBoard(ticketId);
    } catch (error) {
        handleError(error);
    }
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

function setCustomerId(customerId) {
    state.customerId = customerId || state.customerId;
    localStorage.setItem(STORAGE_KEYS.customerId, state.customerId);
    syncCustomerIdentity();
}

function persistTicketId(ticketId) {
    state.selectedTicketId = ticketId;
    localStorage.setItem(STORAGE_KEYS.ticketId, ticketId);
}

function syncCustomerIdentity() {
    text("customerIdentity", state.customerId);
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
