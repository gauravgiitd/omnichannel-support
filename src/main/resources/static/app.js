const STORAGE_KEYS = {
    taskId: "omnichannel.taskId",
    requestId: "omnichannel.requestId"
};
const AUTO_REFRESH_MS = 15000;

const customerUrlParams = new URLSearchParams(window.location.search);
const requestFromUrl = customerUrlParams.get("request");
const legacyTaskFromUrl = customerUrlParams.get("task");
let autoRefreshHandle = null;
let autoRefreshInFlight = false;

const state = {
    view: "landing",
    user: null,
    tasks: [],
    customerRequests: [],
    contactMappings: [],
    jtbdTypes: [],
    jtbdCustomers: [],
    customerJtbdsByCustomer: {},
    selectedJtbdCustomerId: null,
    agentCustomerFilter: "ALL",
    selectedTaskId: localStorage.getItem(STORAGE_KEYS.taskId),
    selectedRequestId: normalizeCustomerRequestId(requestFromUrl || legacyTaskFromUrl || localStorage.getItem(STORAGE_KEYS.requestId)),
    currentTask: null,
    currentRequest: null,
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
    if (state.view === "jtbd") {
        await refreshJtbdDashboard();
        startAutoRefresh();
        return;
    }
    await refreshBoard();
    startAutoRefresh();
});

document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
        void runAutoRefresh();
    }
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
    bindClick("refreshBoard", () => refreshBoard(state.view === "customer" ? state.selectedRequestId : state.selectedTaskId));
    bindClick("toggleStartSupport", toggleStartSupport);
    bindClick("refreshAdmin", refreshAdminDashboard);
    bindClick("resetContactMappingForm", resetContactMappingForm);
    bindClick("refreshJtbdView", refreshJtbdDashboard);
    bindClick("resetJtbdTypeForm", resetJtbdTypeForm);
    const agentCustomerFilter = el("agentCustomerFilter");
    if (agentCustomerFilter) {
        agentCustomerFilter.addEventListener("change", (event) => {
            state.agentCustomerFilter = event.target.value || "ALL";
            renderQueueBoard();
        });
    }
}

function bindForms() {
    bindSubmit("appForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const response = await api("/v1/customers/me/requests", {
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
            await api(`/v1/customers/me/requests/${encodeURIComponent(response.data.request_id)}/documents`, {
                method: "POST",
                body: {
                    channel: "UI",
                    sender_type: "CUSTOMER",
                    sender_identifier: state.user.email,
                    file_url: file.file_token,
                    document_type: "customer_upload",
                    message_body: "Customer uploaded a document while opening the request.",
                    metadata: {
                        source: "customer_app_upload",
                        drive_file_id: driveFileIdFromToken(file.file_token),
                        file_name: file.file_name,
                        mime_type: file.mime_type
                    }
                }
            });
        }

        pushEvent("Customer created a new request", `${response.data.title} was opened from the app.`);
        await refreshBoard(response.data.request_id);
    });

    bindSubmit("replyForm", async (event) => {
        ensureTaskSelected();
        const data = new FormData(event.currentTarget);
        const body = data.get("body");
        const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));

        const messageResponse = await api(`/v1/tasks/${state.selectedTaskId}/messages`, {
            method: "POST",
            body: {
                channel: state.currentTask ? state.currentTask.source_channel : "UI",
                sender_type: "AGENT",
                sender_identifier: state.user.email,
                body,
                attachment_urls: [],
                metadata: { source: "agent_workspace" }
            }
        });

        for (const file of uploadedFiles) {
            await api(`/v1/tasks/${state.selectedTaskId}/documents`, {
                method: "POST",
                body: {
                    channel: state.currentTask ? state.currentTask.source_channel : "UI",
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

        const origin = state.currentTask ? channelLabel(state.currentTask.source_channel) : "APP";
        const deliveryStatus = messageResponse?.data?.metadata?.delivery_status;
        const deliveryError = messageResponse?.data?.metadata?.delivery_error;
        if (deliveryStatus === "failed") {
            pushEvent(
                "Agent response recorded",
                `The reply was added to the task thread, but delivery to ${origin} failed${deliveryError ? `: ${deliveryError}` : "."}`
            );
        } else {
            pushEvent("Agent responded", `The reply was delivered to ${origin} and recorded in the shared task thread.`);
        }
        await refreshBoard(state.selectedTaskId);
    });

    bindSubmit("patchForm", async (event) => {
        ensureTaskSelected();
        const data = new FormData(event.currentTarget);
        const response = await api(`/v1/tasks/${state.selectedTaskId}`, {
            method: "PATCH",
            body: pruneEmpty({
                status: data.get("status")
            })
        });

        pushEvent("Task updated", `${response.data.task_id} is now ${response.data.status}.`);
        await refreshBoard(response.data.task_id);
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
            `Deleted ${response.data.deleted_tasks} tasks, ${response.data.deleted_documents} documents, ${response.data.deleted_messages} messages, ${response.data.deleted_identity_links} identity links, ${response.data.deleted_drive_files} Drive files, and ${response.data.deleted_drive_folders} Drive folders. Email-to-phone mappings were preserved.`
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
            `Deleted ${response.data.deleted_tasks} tasks, ${response.data.deleted_documents} documents, ${response.data.deleted_messages} messages, ${response.data.deleted_identity_links} identity links, ${response.data.deleted_drive_files} Drive files, and ${response.data.deleted_drive_folders} Drive folders for ${response.data.customer_id}. Email-to-phone mappings were preserved.`
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

    bindSubmit("taskDeliveryDebugForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const taskId = `${data.get("taskId") || ""}`.trim();
        const response = await api(`/v1/admin/tasks/${encodeURIComponent(taskId)}/delivery-debug`);
        const debug = response.data;
        text(
            "taskDeliveryDebugSummary",
            `Task ${debug.task_id} belongs to ${debug.customer_id}, started on ${debug.source_channel}, and will currently reply to ${debug.resolved_outbound_recipient || "no resolved recipient"}.`
        );
        text("taskDeliveryDebugResult", JSON.stringify(debug, null, 2));
    });

    bindSubmit("jtbdTypeForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const jtbdTypeId = `${data.get("jtbdTypeId") || ""}`.trim();
        const payload = {
            name: data.get("name"),
            description: blankOrNull(data.get("description")),
            stages: parseJtbdStages(data.get("stages"))
        };
        const path = jtbdTypeId ? `/v1/admin/jtbds/types/${jtbdTypeId}` : "/v1/admin/jtbds/types";
        const method = jtbdTypeId ? "PUT" : "POST";
        const response = await api(path, {
            method,
            body: payload
        });
        text("jtbdTypeResult", `Saved JTBD type ${response.data.name}.`);
        resetJtbdTypeForm();
        await refreshJtbdDashboard();
    });

    bindSubmit("customerJtbdForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const customerId = `${data.get("customerId") || ""}`.trim();
        const response = await api(`/v1/admin/jtbds/customers/${encodeURIComponent(customerId)}/instances`, {
            method: "POST",
            body: {
                jtbdTypeId: data.get("jtbdTypeId")
            }
        });
        state.selectedJtbdCustomerId = customerId;
        text("customerJtbdResult", `Created JTBD ${response.data.jtbd_type_name} for ${customerId}.`);
        await refreshJtbdDashboard();
    });
}

async function refreshBoard(preferredTaskId) {
    if (state.view === "customer") {
        const customerResponse = await api("/v1/customers/me/requests");
        state.customerRequests = customerResponse.data;
    } else {
        const response = await api("/v1/tasks");
        state.tasks = response.data;
    }

    renderMetrics();
    renderQueueBoard();
    renderCustomerTaskList();

    const targetId = preferredTaskId || (state.view === "customer" ? state.selectedRequestId : state.selectedTaskId) || findRelevantTaskId();
    if (targetId) {
        if (state.view === "customer") {
            await selectCustomerRequest(targetId);
        } else {
            await selectTask(targetId);
        }
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

async function refreshJtbdDashboard() {
    if (state.view !== "jtbd") {
        return;
    }
    const [typeResponse, customerResponse] = await Promise.all([
        api("/v1/admin/jtbds/types"),
        api("/v1/admin/jtbds/customers")
    ]);
    state.jtbdTypes = typeResponse.data;
    state.jtbdCustomers = customerResponse.data;

    const instanceResponses = await Promise.all(
        state.jtbdCustomers.map((customer) =>
            api(`/v1/admin/jtbds/customers/${encodeURIComponent(customer.customer_id)}/instances`)
                .then((payload) => [customer.customer_id, payload.data]))
    );
    state.customerJtbdsByCustomer = Object.fromEntries(instanceResponses);
    if (!state.selectedJtbdCustomerId && state.jtbdCustomers.length) {
        state.selectedJtbdCustomerId = state.jtbdCustomers[0].customer_id;
    }
    populateJtbdSelects();
    renderJtbdTypes();
    renderJtbdCustomers();
}

function startAutoRefresh() {
    if (autoRefreshHandle || state.view === "landing" || state.view === "login") {
        return;
    }
    autoRefreshHandle = window.setInterval(() => {
        void runAutoRefresh();
    }, AUTO_REFRESH_MS);
}

async function runAutoRefresh() {
    if (autoRefreshInFlight || document.hidden) {
        return;
    }
    autoRefreshInFlight = true;
    try {
        if (state.view === "admin") {
            await refreshAdminDashboard();
            return;
        }
        if (state.view === "jtbd") {
            await refreshJtbdDashboard();
            return;
        }
        const preferredId = state.view === "customer" ? state.selectedRequestId : state.selectedTaskId;
        await refreshBoard(preferredId);
    } catch (error) {
        console.warn("Auto-refresh failed", error);
    } finally {
        autoRefreshInFlight = false;
    }
}

function findRelevantTaskId() {
    const list = state.view === "customer" ? state.customerRequests : state.tasks;
    if (!list[0]) {
        return null;
    }
    return state.view === "customer" ? list[0].request_id : list[0].task_id;
}

async function selectTask(taskId) {
    persistTaskId(taskId);
    const [taskResponse, messageResponse, documentResponse] = await Promise.all([
        api(`/v1/tasks/${taskId}`),
        api(`/v1/tasks/${taskId}/messages`),
        api(`/v1/tasks/${taskId}/documents`)
    ]);

    state.currentTask = taskResponse.data;
    state.messages = messageResponse.data;
    state.documents = documentResponse.data;

    syncFormsWithTask();
    renderAgentWorkspace();
    renderCustomerExperience();
    renderCustomerTaskList();
    renderQueueBoard();
}

async function selectCustomerRequest(requestId) {
    persistRequestId(requestId);
    const [requestResponse, messageResponse, documentResponse] = await Promise.all([
        api(`/v1/customers/me/requests/${encodeURIComponent(requestId)}`),
        api(`/v1/customers/me/requests/${encodeURIComponent(requestId)}/messages`),
        api(`/v1/customers/me/requests/${encodeURIComponent(requestId)}/documents`)
    ]);

    state.currentRequest = requestResponse.data;
    state.messages = messageResponse.data;
    state.documents = documentResponse.data;

    renderCustomerExperience();
    renderCustomerTaskList();
}

function renderMetrics() {
    const metricsEl = el("metrics");
    if (!metricsEl) {
        return;
    }

    const metrics = [
        { label: "Total tasks", value: state.tasks.length, copy: "All omnichannel issues" },
        { label: "Open tasks", value: state.tasks.filter((task) => !["RESOLVED", "CLOSED"].includes(task.status)).length, copy: "Still active with support" },
        { label: "Customers", value: new Set(state.tasks.map((task) => task.customer_id)).size, copy: "Customers represented" }
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
        { label: "Tasks", value: summary.tasks, copy: "Total tasks in database" },
        { label: "Documents", value: summary.documents, copy: "Stored task documents" },
        { label: "Messages", value: summary.messages, copy: "Conversation messages" },
        { label: "Contact mappings", value: summary.contact_mappings, copy: "Strict email to phone mappings" },
        { label: "Identity links", value: summary.identity_links, copy: "Customer identifiers" },
        { label: "Audit logs", value: summary.audit_logs, copy: "Recorded audit events" },
        { label: "Merges", value: summary.merges, copy: "Task merge mappings" }
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
        <article class="task-card mapping-card" data-mapping-id="${mapping.id}">
            <div class="bubble-meta">
                <strong>${escapeHtml(mapping.email)}</strong>
                <span class="badge">${escapeHtml(mapping.phone)}</span>
            </div>
            <p class="task-supporting">Updated ${formatDate(mapping.updated_at)}</p>
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

function renderJtbdTypes() {
    const container = el("jtbdTypeList");
    if (!container) {
        return;
    }
    if (!state.jtbdTypes.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No JTBD types yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = state.jtbdTypes.map((type) => `
        <article class="task-card mapping-card" data-jtbd-type-id="${type.public_id}">
            <div class="bubble-meta">
                <strong>${escapeHtml(type.name)}</strong>
                <span class="badge">${type.stages.length} stages</span>
            </div>
            <p class="task-supporting">${escapeHtml(type.description || "No description")}</p>
            <p class="small-note">${type.stages.map((stage) => `${stage.stage_name}${stage.terminal_completed ? " (completed)" : ""}`).join(" -> ")}</p>
            <div class="actions-inline">
                <button type="button" class="ghost-button jtbd-type-edit" data-jtbd-type-id="${type.public_id}">Edit</button>
                <button type="button" class="ghost-button jtbd-type-delete" data-jtbd-type-id="${type.public_id}">Delete</button>
            </div>
        </article>
    `).join("");
    container.querySelectorAll(".jtbd-type-edit").forEach((button) => {
        button.addEventListener("click", () => populateJtbdTypeForm(button.dataset.jtbdTypeId));
    });
    container.querySelectorAll(".jtbd-type-delete").forEach((button) => {
        button.addEventListener("click", () => deleteJtbdType(button.dataset.jtbdTypeId).catch(handleError));
    });
}

function renderJtbdCustomers() {
    const container = el("jtbdCustomerList");
    if (!container) {
        return;
    }
    if (!state.jtbdCustomers.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No customers yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = state.jtbdCustomers.map((customer) => {
        const instances = state.customerJtbdsByCustomer[customer.customer_id] || [];
        return `
            <article class="task-card customer-task-card ${state.selectedJtbdCustomerId === customer.customer_id ? "active expanded" : ""}">
                <div class="customer-task-summary">
                    <div class="bubble-meta">
                        <strong>${escapeHtml(customer.customer_id)}</strong>
                        <span class="badge">${customer.active_jtbd_count} active JTBDs</span>
                    </div>
                    <p class="task-supporting">${[...(customer.emails || []), ...(customer.phones || [])].join(" • ") || "No identifiers"}</p>
                </div>
                <div class="queue-stack">
                    ${instances.length ? instances.map((instance) => `
                        <div class="task-card mapping-card">
                            <div class="bubble-meta">
                                <strong>${escapeHtml(instance.jtbd_type_name)}</strong>
                                <span class="badge">${escapeHtml(instance.status)}</span>
                            </div>
                            <p class="task-supporting">${escapeHtml(instance.public_id)} • ${escapeHtml(instance.current_stage_name)}</p>
                            <div class="actions-inline">
                                <select class="jtbd-stage-select" data-customer-jtbd-id="${instance.public_id}">
                                    ${instance.stages.map((stage) => `
                                        <option value="${stage.stage_key}" ${stage.stage_key === instance.current_stage_key ? "selected" : ""}>
                                            ${escapeHtml(stage.stage_name)}${stage.terminal_completed ? " (completed)" : ""}
                                        </option>
                                    `).join("")}
                                </select>
                                <button type="button" class="ghost-button jtbd-instance-update" data-customer-jtbd-id="${instance.public_id}">Update stage</button>
                                <button type="button" class="ghost-button jtbd-instance-delete" data-customer-jtbd-id="${instance.public_id}">Delete</button>
                            </div>
                        </div>
                    `).join("") : `<div class="empty-state">No JTBDs on this customer yet.</div>`}
                </div>
            </article>
        `;
    }).join("");
    container.querySelectorAll(".jtbd-instance-update").forEach((button) => {
        button.addEventListener("click", () => updateCustomerJtbd(button.dataset.customerJtbdId).catch(handleError));
    });
    container.querySelectorAll(".jtbd-instance-delete").forEach((button) => {
        button.addEventListener("click", () => deleteCustomerJtbd(button.dataset.customerJtbdId).catch(handleError));
    });
}

function renderQueueBoard() {
    const queueBoard = el("queueBoard");
    if (!queueBoard) {
        return;
    }

    populateAgentCustomerFilter();
    const visibleTasks = filteredAgentTasks();
    const groups = [
        { title: "Open tasks", className: "triage", matcher: (_queue, task) => !["RESOLVED", "CLOSED"].includes(task.status) },
        { title: "Closed tasks", className: "closed", matcher: (_queue, task) => ["RESOLVED", "CLOSED"].includes(task.status) }
    ];

    queueBoard.innerHTML = groups.map((group) => {
        const tasks = visibleTasks.filter((task) => group.matcher(task.assigned_queue, task));
        return `
            <section class="queue-column ${group.className}">
                <h4>${group.title}</h4>
                <div class="queue-stack">
                    ${tasks.length ? tasks.map(renderTaskCard).join("") : `<div class="empty-state">No tasks here.</div>`}
                </div>
            </section>
        `;
    }).join("");

    queueBoard.querySelectorAll(".task-card").forEach((card) => {
        card.addEventListener("click", () => selectTask(card.dataset.taskId).catch(handleError));
    });
}

function renderTaskCard(task) {
    const active = state.selectedTaskId === task.task_id ? "active" : "";
    const missingData = !task.policy_id && !task.claim_id;
    const jtbdCopy = task.customer_jtbd_type_name ? ` • JTBD ${task.customer_jtbd_type_name}` : "";

    return `
        <article class="task-card ${active}" data-task-id="${task.task_id}">
            <div class="bubble-meta">
                <strong>${task.task_id}</strong>
                <span class="status-pill ${missingData ? "warning" : "good"}">${missingData ? "missing data" : "ready"}</span>
            </div>
            <div class="bubble-meta">
                <span class="badge">${task.source_channel}</span>
                <span class="badge">${task.status}</span>
            </div>
            <p class="task-supporting">${task.customer_id} • ${task.issue_type || "unclassified"} • ${task.assigned_queue || "triage"}${jtbdCopy}</p>
        </article>
    `;
}

function renderAgentWorkspace() {
    const heading = el("taskHeading");
    if (!heading) {
        return;
    }
    if (!state.currentTask) {
        clearWorkspace();
        return;
    }

    heading.textContent = `${state.currentTask.task_id} • ${state.currentTask.issue_type || "unclassified"}`;
    const meta = el("taskMeta");
    if (meta) {
        meta.innerHTML = [
            badge(state.currentTask.source_channel),
            badge(state.currentTask.status),
            badge(state.currentTask.assigned_queue || "triage"),
            state.currentTask.policy_id ? badge(`Policy ${state.currentTask.policy_id}`) : "",
            state.currentTask.claim_id ? badge(`Claim ${state.currentTask.claim_id}`) : ""
        ].join("");
    }
    const jtbdMeta = el("taskJtbdMeta");
    if (jtbdMeta) {
        jtbdMeta.textContent = state.currentTask.customer_jtbd_id
            ? `JTBD ${state.currentTask.customer_jtbd_type_name} • Stage ${state.currentTask.customer_jtbd_stage_name} • ${state.currentTask.customer_jtbd_status}`
            : "No JTBD linked to this task.";
    }

    text("timelineCount", `${state.messages.length} messages`);
    text("documentCount", `${state.documents.length} docs`);
    renderChatThread("messageTimeline", state.messages, false);
    renderDocuments();
}

function filteredAgentTasks() {
    if (state.view !== "agent" || state.agentCustomerFilter === "ALL") {
        return state.tasks;
    }
    return (state.tasks || []).filter((task) => task.customer_id === state.agentCustomerFilter);
}

function populateAgentCustomerFilter() {
    const select = el("agentCustomerFilter");
    if (!select || state.view !== "agent") {
        return;
    }
    const customers = [...new Set((state.tasks || []).map((task) => task.customer_id))].sort();
    const current = state.agentCustomerFilter || "ALL";
    select.innerHTML = `
        <option value="ALL">All customers</option>
        ${customers.map((customerId) => `<option value="${escapeHtml(customerId)}">${escapeHtml(customerId)}</option>`).join("")}
    `;
    select.value = customers.includes(current) ? current : "ALL";
    state.agentCustomerFilter = select.value;
}

function renderCustomerExperience() {
    const customerHeading = el("customerAppHeading");
    if (!customerHeading) {
        return;
    }
    if (!state.currentRequest) {
        customerHeading.textContent = "Your requests";
        text("customerAppSubhead", "Click any request below to expand its full conversation and continue the journey.");
        text("customerStatusPill", "Ready");
        return;
    }

    customerHeading.textContent = state.currentRequest.title || "Selected request";
    text(
        "customerAppSubhead",
        "The expanded request shows the full customer journey, including email, WhatsApp, app updates, and documents."
    );
    text("customerStatusPill", state.currentRequest.status_label || "Active");
    setStartSupportCollapsed(true);
}

function renderCustomerTaskList() {
    const container = el("customerTaskList");
    if (!container) {
        return;
    }
    const requests = state.customerRequests || [];
    text("customerTaskCount", `${requests.length} request${requests.length === 1 ? "" : "s"}`);
    if (!requests.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No requests yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = requests.map(renderCustomerTaskAccordion).join("");
    container.querySelectorAll(".customer-task-header").forEach((card) => {
        card.addEventListener("click", () => toggleCustomerTask(card.dataset.requestId).catch(handleError));
    });
    hydrateExpandedCustomerTask(container);
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
            ? "No request loaded in the app yet."
            : "Select a task to load the full conversation.";
        return;
    }

    container.className = `chat-thread ${customerView ? "customer-view" : "agent-view"}`;
    container.innerHTML = "";
    messages.forEach((message) => container.appendChild(buildMessageNode(message, customerView)));
}

function renderCustomerTaskAccordion(request) {
    const active = state.selectedRequestId === request.request_id;
    const statusTone = request.status_label === "Completed" ? "good" : "warning";
    return `
        <article class="task-card customer-task-card ${active ? "active expanded" : ""}" data-request-id="${request.request_id}">
            <button type="button" class="customer-task-header" data-request-id="${request.request_id}">
                <div class="customer-task-summary">
                    <div class="bubble-meta">
                        <strong>${escapeHtml(request.title || "Request")}</strong>
                        <span class="status-pill ${statusTone}">${escapeHtml(request.status_label || "Active")}</span>
                    </div>
                    <div class="bubble-meta">
                        <span class="badge">${escapeHtml(request.stage_label || "Open")}</span>
                        <span class="badge">${request.source_channel}</span>
                        ${request.jtbd_backed ? `<span class="badge">JTBD</span>` : `<span class="badge">Request</span>`}
                    </div>
                    <p class="task-supporting">${request.jtbd_type_name || "General support request"} • ${request.internal_task_count} internal work item${request.internal_task_count === 1 ? "" : "s"}</p>
                </div>
                <span class="customer-task-chevron">${active ? "Hide" : "Open"}</span>
            </button>
            ${active ? `
                <div class="customer-task-body" data-task-body="${request.request_id}">
                    <div class="customer-task-details">
                        <span class="badge">Request stage ${escapeHtml(request.stage_label || "Open")}</span>
                        <span class="badge">Status ${escapeHtml(request.status_label || "Active")}</span>
                        ${request.jtbd_type_name ? `<span class="badge">${escapeHtml(request.jtbd_type_name)}</span>` : ""}
                    </div>
                    <div class="chat-thread customer-task-chat empty-state">Loading conversation...</div>
                    <form id="customerComposeForm" class="stack-form compact customer-compose-form">
                        <label>
                            Message
                            <textarea name="body" rows="4" required>I want to continue on this same request.</textarea>
                        </label>
                        <label>
                            Attach documents
                            <input name="attachments" type="file" multiple>
                        </label>
                        <p class="small-note">For email responses, reply directly from your inbox and we will keep everything on this same request.</p>
                        <button type="submit">Send update to this request</button>
                    </form>
                </div>
            ` : ""}
        </article>
    `;
}

function hydrateExpandedCustomerTask(container) {
    if (!container || !state.currentRequest || state.selectedRequestId !== state.currentRequest.request_id) {
        return;
    }
    const body = container.querySelector(`[data-task-body="${state.currentRequest.request_id}"]`);
    if (!body) {
        return;
    }
    const chatContainer = body.querySelector(".customer-task-chat");
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

function syncFormsWithTask() {
    if (!state.currentTask) {
        return;
    }
    setFormValue("#patchForm [name='status']", state.currentTask.status || "");
}

function clearWorkspace() {
    if (state.view === "customer") {
        state.currentRequest = null;
        state.messages = [];
        state.documents = [];
        persistRequestId(null);
        renderCustomerExperience();
        renderCustomerTaskList();
        setStartSupportCollapsed(false);
        return;
    }
    text("taskHeading", "Select a task");
    html("taskMeta", "");
    text("taskJtbdMeta", "");
    renderChatThread("messageTimeline", [], false);
    renderChatThread("customerChat", [], true);
    const documentList = el("documentList");
    if (documentList) {
        documentList.className = "document-list empty-state";
        documentList.textContent = "Documents shared from any channel appear here.";
    }
    state.currentTask = null;
    state.messages = [];
    state.documents = [];
    persistTaskId(null);
    renderCustomerExperience();
    renderCustomerTaskList();
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
    button.textContent = collapsed ? "Open new request" : "Hide new request form";
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

function resetJtbdTypeForm() {
    const form = el("jtbdTypeForm");
    if (!form) {
        return;
    }
    form.reset();
    const jtbdTypeId = form.querySelector("[name='jtbdTypeId']");
    if (jtbdTypeId) {
        jtbdTypeId.value = "";
    }
    text("jtbdTypeResult", "");
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

function populateJtbdTypeForm(jtbdTypeId) {
    const type = (state.jtbdTypes || []).find((item) => item.public_id === jtbdTypeId);
    const form = el("jtbdTypeForm");
    if (!type || !form) {
        return;
    }
    form.querySelector("[name='jtbdTypeId']").value = type.public_id;
    form.querySelector("[name='name']").value = type.name;
    form.querySelector("[name='description']").value = type.description || "";
    form.querySelector("[name='stages']").value = type.stages
        .map((stage) => `${stage.stage_key}:${stage.stage_name}${stage.terminal_completed ? "*" : ""}`)
        .join("\n");
    text("jtbdTypeResult", `Editing JTBD type ${type.name}`);
}

async function deleteContactMapping(mappingId) {
    await api(`/v1/admin/contact-mappings/${mappingId}`, {
        method: "DELETE"
    });
    text("contactMappingResult", "Mapping deleted.");
    resetContactMappingForm();
    await refreshAdminDashboard();
}

async function deleteJtbdType(jtbdTypeId) {
    await api(`/v1/admin/jtbds/types/${jtbdTypeId}`, {
        method: "DELETE"
    });
    text("jtbdTypeResult", "JTBD type deleted.");
    resetJtbdTypeForm();
    await refreshJtbdDashboard();
}

async function updateCustomerJtbd(customerJtbdId) {
    const selector = document.querySelector(`.jtbd-stage-select[data-customer-jtbd-id="${customerJtbdId}"]`);
    const response = await api(`/v1/admin/jtbds/instances/${customerJtbdId}`, {
        method: "PUT",
        body: {
            stageKey: selector ? selector.value : ""
        }
    });
    text("customerJtbdResult", `Updated ${response.data.jtbd_type_name} to ${response.data.current_stage_name}.`);
    await refreshJtbdDashboard();
}

async function deleteCustomerJtbd(customerJtbdId) {
    await api(`/v1/admin/jtbds/instances/${customerJtbdId}`, {
        method: "DELETE"
    });
    text("customerJtbdResult", "Customer JTBD deleted.");
    await refreshJtbdDashboard();
}

function populateJtbdSelects() {
    const customerSelect = el("jtbdCustomerSelect");
    if (customerSelect) {
        customerSelect.innerHTML = state.jtbdCustomers.map((customer) => `
            <option value="${customer.customer_id}" ${customer.customer_id === state.selectedJtbdCustomerId ? "selected" : ""}>
                ${escapeHtml(customer.customer_id)}${customer.emails?.length ? ` • ${escapeHtml(customer.emails[0])}` : ""}
            </option>
        `).join("");
        customerSelect.addEventListener("change", () => {
            state.selectedJtbdCustomerId = customerSelect.value;
        });
    }

    const typeSelect = el("jtbdTypeSelect");
    if (typeSelect) {
        typeSelect.innerHTML = state.jtbdTypes.map((type) => `
            <option value="${type.public_id}">${escapeHtml(type.name)}</option>
        `).join("");
    }
}

function ensureTaskSelected() {
    if (!state.selectedTaskId) {
        throw new Error("Select a task first.");
    }
}

async function submitCustomerComposeForm(event) {
    if (!state.selectedRequestId) {
        throw new Error("Select a request first.");
    }
    const data = new FormData(event.currentTarget);
    const body = data.get("body");
    const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));

    if (uploadedFiles.length) {
        for (let index = 0; index < uploadedFiles.length; index += 1) {
            const file = uploadedFiles[index];
            await api(`/v1/customers/me/requests/${encodeURIComponent(state.selectedRequestId)}/documents`, {
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
        await api(`/v1/customers/me/requests/${encodeURIComponent(state.selectedRequestId)}/messages`, {
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

    pushEvent("Customer continued an existing request", "A new customer update was added to the same request journey.");
    await refreshBoard(state.selectedRequestId);
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

function syncTaskUrl(taskId) {
    if (state.view !== "customer" || !window.history || !window.location) {
        return;
    }
    const url = new URL(window.location.href);
    if (taskId) {
        url.searchParams.set("task", taskId);
    } else {
        url.searchParams.delete("task");
    }
    window.history.replaceState({}, "", url);
}

function syncRequestUrl(requestId) {
    if (state.view !== "customer" || !window.history || !window.location) {
        return;
    }
    const url = new URL(window.location.href);
    if (requestId) {
        url.searchParams.set("request", requestId);
    } else {
        url.searchParams.delete("request");
    }
    url.searchParams.delete("task");
    window.history.replaceState({}, "", url);
}

async function loadSession() {
    const response = await api("/v1/me");
    state.user = response.data;
}

function persistTaskId(taskId) {
    state.selectedTaskId = taskId;
    if (taskId) {
        localStorage.setItem(STORAGE_KEYS.taskId, taskId);
    } else {
        localStorage.removeItem(STORAGE_KEYS.taskId);
    }
    syncTaskUrl(taskId);
}

function persistRequestId(requestId) {
    state.selectedRequestId = requestId;
    if (requestId) {
        localStorage.setItem(STORAGE_KEYS.requestId, requestId);
    } else {
        localStorage.removeItem(STORAGE_KEYS.requestId);
    }
    syncRequestUrl(requestId);
}

async function toggleCustomerTask(taskId) {
    if (state.view === "customer" && state.selectedRequestId === taskId) {
        clearWorkspace();
        return;
    }
    await selectCustomerRequest(taskId);
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
    text("jtbdIdentity", state.user.roles && state.user.roles.includes("ROLE_ADMIN")
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

function parseJtbdStages(value) {
    return `${value || ""}`.split("\n")
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line, index) => {
            const terminalCompleted = line.endsWith("*");
            const cleaned = terminalCompleted ? line.slice(0, -1).trim() : line;
            const [rawKey, ...nameParts] = cleaned.split(":");
            const stageName = nameParts.length ? nameParts.join(":").trim() : rawKey.trim();
            const stageKey = nameParts.length ? rawKey.trim() : "";
            return {
                stageKey: stageKey || null,
                stageName,
                stageOrder: index + 1,
                terminalCompleted
            };
        });
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

function normalizeCustomerRequestId(value) {
    if (!value) {
        return null;
    }
    if (value.startsWith("jtbd_") || value.startsWith("task_")) {
        return value;
    }
    if (value.startsWith("TSK-")) {
        return `task_${value}`;
    }
    return value;
}

function encodeDriveAttachmentToken(file) {
    const fileId = encodeURIComponent(driveFileIdFromToken(file.file_token) || "");
    const fileName = encodeURIComponent(file.file_name || "");
    const mimeType = encodeURIComponent(file.mime_type || "");
    return `drive://${fileId}?name=${fileName}&mime=${mimeType}`;
}
