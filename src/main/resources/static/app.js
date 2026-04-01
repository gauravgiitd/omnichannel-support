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
    agentCustomers: [],
    agentWorkspace: null,
    customerRequests: [],
    contactMappings: [],
    jtbdTypes: [],
    jtbdCustomers: [],
    customerJtbdsByCustomer: {},
    selectedJtbdCustomerId: null,
    agentCustomerFilter: "",
    agentDomainFilter: "ALL",
    selectedTaskId: localStorage.getItem(STORAGE_KEYS.taskId),
    selectedRequestId: normalizeCustomerRequestId(requestFromUrl || legacyTaskFromUrl || localStorage.getItem(STORAGE_KEYS.requestId)),
    expertMessageScope: "relevant",
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
    bindClick("refreshBoard", () => refreshBoard(
        state.view === "customer"
            ? state.selectedRequestId
            : state.view === "agent"
                ? state.agentCustomerFilter
                : state.selectedTaskId
    ));
    bindClick("toggleStartSupport", toggleStartSupport);
    bindClick("refreshAdmin", refreshAdminDashboard);
    bindClick("resetContactMappingForm", resetContactMappingForm);
    bindClick("refreshJtbdView", refreshJtbdDashboard);
    bindClick("resetJtbdTypeForm", resetJtbdTypeForm);
    const agentCustomerFilter = el("agentCustomerFilter");
    if (agentCustomerFilter) {
        agentCustomerFilter.addEventListener("change", (event) => {
            state.agentCustomerFilter = event.target.value || "";
            if (state.view === "agent" && state.agentCustomerFilter) {
                void selectAgentCustomer(state.agentCustomerFilter);
            }
        });
    }
    const agentDomainFilter = el("agentDomainFilter");
    if (agentDomainFilter) {
        agentDomainFilter.addEventListener("change", (event) => {
            state.agentDomainFilter = event.target.value || "ALL";
            renderAgentWorkspace();
        });
    }
    bindClick("expertScopeRelevant", () => setExpertMessageScope("relevant"));
    bindClick("expertScopeConversation", () => setExpertMessageScope("conversation"));
}

function bindForms() {
    bindSubmit("customerComposeForm", submitCustomerComposeForm);

    bindSubmit("replyForm", async (event) => {
        const data = new FormData(event.currentTarget);
        const body = data.get("body");
        const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));

        if (state.view === "agent") {
            ensureAgentCustomerSelected();
            const query = state.selectedTaskId ? `?taskId=${encodeURIComponent(state.selectedTaskId)}` : "";
            const messageResponse = await api(`/v1/agent/customers/${encodeURIComponent(state.agentCustomerFilter)}/messages${query}`, {
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
                await api(`/v1/agent/customers/${encodeURIComponent(state.agentCustomerFilter)}/documents${query}`, {
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
                    `The reply was added to the conversation, but delivery to ${origin} failed${deliveryError ? `: ${deliveryError}` : "."}`
                );
            } else {
                pushEvent("Agent responded", `The reply was recorded in the shared conversation${state.selectedTaskId ? ` on ${state.selectedTaskId}` : ""}.`);
            }
            await refreshBoard(state.agentCustomerFilter);
            return;
        }

        ensureTaskSelected();

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

    bindSubmit("expertReplyForm", async (event) => {
        ensureTaskSelected();
        const data = new FormData(event.currentTarget);
        const body = data.get("body");
        const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));

        await api(`/v1/expert/tasks/${state.selectedTaskId}/messages`, {
            method: "POST",
            body: {
                channel: "UI",
                sender_type: "EXPERT",
                sender_identifier: state.user.email,
                body,
                attachment_urls: [],
                metadata: { source: "expert_workspace" }
            }
        });

        for (const file of uploadedFiles) {
            await api(`/v1/expert/tasks/${state.selectedTaskId}/documents`, {
                method: "POST",
                body: {
                    channel: "UI",
                    sender_type: "EXPERT",
                    sender_identifier: state.user.email,
                    file_url: file.file_token,
                    document_type: "expert_attachment",
                    message_body: "Expert attached a supporting document.",
                    metadata: {
                        source: "expert_workspace_upload",
                        drive_file_id: driveFileIdFromToken(file.file_token),
                        file_name: file.file_name,
                        mime_type: file.mime_type
                    }
                }
            });
        }

        pushEvent("Expert update saved", "The expert note and any supporting documents were added to the task record.");
        await refreshBoard(state.selectedTaskId);
    });

    bindSubmit("expertPatchForm", async (event) => {
        ensureTaskSelected();
        const data = new FormData(event.currentTarget);
        const response = await api(`/v1/expert/tasks/${state.selectedTaskId}`, {
            method: "PATCH",
            body: pruneEmpty({
                assigned_agent: data.get("assignedAgent"),
                status: data.get("status")
            })
        });

        pushEvent("Expert task updated", `${response.data.task_id} is now ${response.data.status || "updated"}.`);
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
    } else if (state.view === "agent") {
        const response = await api("/v1/agent/customers");
        state.agentCustomers = response.data;
    } else if (state.view === "expert") {
        const response = await api("/v1/expert/tasks");
        state.tasks = response.data;
    } else {
        const response = await api("/v1/tasks");
        state.tasks = response.data;
    }

    renderMetrics();
    renderQueueBoard();
    renderCustomerTaskList();

    if (state.view === "agent") {
        const targetCustomerId = preferredTaskId || state.agentCustomerFilter || findRelevantTaskId();
        if (targetCustomerId) {
            await selectAgentCustomer(targetCustomerId);
        } else {
            clearWorkspace();
        }
        return;
    }

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
        const preferredId = state.view === "customer"
            ? state.selectedRequestId
            : state.view === "agent"
                ? state.agentCustomerFilter
                : state.selectedTaskId;
        await refreshBoard(preferredId);
    } catch (error) {
        console.warn("Auto-refresh failed", error);
    } finally {
        autoRefreshInFlight = false;
    }
}

function findRelevantTaskId() {
    const list = state.view === "customer" ? state.customerRequests : state.view === "agent" ? state.agentCustomers : state.tasks;
    if (!list[0]) {
        return null;
    }
    if (state.view === "customer") {
        return list[0].request_id;
    }
    if (state.view === "agent") {
        return list[0].customer_id;
    }
    return list[0].task_id;
}

async function selectTask(taskId) {
    persistTaskId(taskId);
    const basePath = state.view === "expert" ? "/v1/expert/tasks" : "/v1/tasks";
    const messagePath = state.view === "expert"
        ? `${basePath}/${taskId}/messages?scope=${encodeURIComponent(state.expertMessageScope)}`
        : `${basePath}/${taskId}/messages`;
    const [taskResponse, messageResponse, documentResponse] = await Promise.all([
        api(`${basePath}/${taskId}`),
        api(messagePath),
        api(`${basePath}/${taskId}/documents`)
    ]);

    state.currentTask = taskResponse.data;
    state.messages = messageResponse.data;
    state.documents = documentResponse.data;

    syncFormsWithTask();
    if (state.view === "expert") {
        renderExpertWorkspace();
        renderExpertTaskBoard();
    } else {
        renderAgentWorkspace();
    }
    renderCustomerExperience();
    renderCustomerTaskList();
    renderQueueBoard();
}

async function selectAgentCustomer(customerId) {
    state.agentCustomerFilter = customerId;
    const response = await api(`/v1/agent/customers/${encodeURIComponent(customerId)}/workspace`);
    state.agentWorkspace = response.data;
    state.tasks = response.data.tasks || [];
    state.messages = response.data.messages || [];
    state.documents = response.data.documents || [];
    persistTaskId(resolveAgentSelectedTask(response.data));
    state.currentTask = (state.tasks || []).find((task) => task.task_id === state.selectedTaskId) || null;
    populateAgentCustomerFilter();
    populateAgentDomainFilter();
    renderMetrics();
    renderAgentCustomerList();
    renderAgentWorkspace();
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

    const metrics = state.view === "agent"
        ? [
            { label: "Customers", value: state.agentCustomers.length, copy: "Customers with tracked support activity" },
            { label: "Visible JTBDs", value: filteredAgentJtbds().length, copy: "Requests visible in this workspace" },
            { label: "Visible tasks", value: filteredAgentTasks().length, copy: "Internal work items for the selected customer" }
        ]
        : state.view === "expert"
        ? [
            { label: "Expert tasks", value: state.tasks.length, copy: "Tasks in the expert execution tier" },
            { label: "Open tasks", value: state.tasks.filter((task) => !["RESOLVED", "CLOSED"].includes(task.status)).length, copy: "Still need expert action" },
            { label: "Assigned to me", value: state.tasks.filter((task) => (task.assigned_agent || "").toLowerCase() === (state.user?.email || "").toLowerCase()).length, copy: "Currently owned by your expert account" }
        ]
        : [
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
    if (state.view === "expert") {
        renderExpertTaskBoard();
        return;
    }
    if (state.view === "agent") {
        renderAgentCustomerList();
        return;
    }
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

function renderExpertTaskBoard() {
    const board = el("expertTaskBoard");
    if (!board) {
        return;
    }
    const groups = [
        { title: "Open expert tasks", className: "triage", matcher: (task) => !["RESOLVED", "CLOSED"].includes(task.status) },
        { title: "Closed expert tasks", className: "closed", matcher: (task) => ["RESOLVED", "CLOSED"].includes(task.status) }
    ];
    board.innerHTML = groups.map((group) => {
        const tasks = (state.tasks || []).filter(group.matcher);
        return `
            <section class="queue-column ${group.className}">
                <h4>${group.title}</h4>
                <div class="queue-stack">
                    ${tasks.length ? tasks.map(renderTaskCard).join("") : `<div class="empty-state">No tasks here.</div>`}
                </div>
            </section>
        `;
    }).join("");
    board.querySelectorAll(".task-card").forEach((card) => {
        card.addEventListener("click", () => selectTask(card.dataset.taskId).catch(handleError));
    });
}

function renderTaskCard(task) {
    const active = state.selectedTaskId === task.task_id ? "active" : "";
    const missingData = !task.policy_id && !task.claim_id;
    const jtbdCopy = task.customer_jtbd_type_name ? ` • JTBD ${task.customer_jtbd_type_name}` : "";
    const tierCopy = task.execution_tier ? ` • ${task.execution_tier}` : "";

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
            <p class="task-supporting">${task.customer_id} • ${task.issue_type || "unclassified"} • ${task.assigned_queue || "triage"}${jtbdCopy}${tierCopy}</p>
        </article>
    `;
}

function renderAgentWorkspace() {
    const heading = el("taskHeading");
    if (!heading) {
        return;
    }
    if (!state.agentWorkspace) {
        clearWorkspace();
        return;
    }

    heading.textContent = `${state.agentWorkspace.customer_id} • ${((state.agentWorkspace.emails || [])[0]) || ((state.agentWorkspace.phones || [])[0]) || "customer selected"}`;
    const meta = el("taskMeta");
    if (meta) {
        meta.innerHTML = [
            badge(state.agentWorkspace.primary_channel || "UI"),
            badge(`${filteredAgentJtbds().length} JTBDs`),
            badge(`${filteredAgentTasks().length} tasks`),
            state.selectedTaskId ? badge(`Reply target ${state.selectedTaskId}`) : badge("No task selected")
        ].join("");
    }
    const jtbdMeta = el("taskJtbdMeta");
    if (jtbdMeta) {
        jtbdMeta.textContent = state.agentDomainFilter === "ALL"
            ? "Full conversation is visible here. Narrow to a domain when you want a focused working view."
            : `Domain-focused view on ${state.agentDomainFilter}. Switch back to All domains for the full conversation.`;
    }

    const filteredMessages = filteredAgentMessages();
    const filteredDocuments = filteredAgentDocuments();
    text("timelineCount", `${filteredMessages.length} messages in view`);
    text("documentCount", `${filteredDocuments.length} docs`);
    renderChatThread("messageTimeline", filteredMessages, false);
    renderDocumentsFromList("documentList", filteredDocuments, "Documents shared from any channel appear here.");
    renderAgentJtbds();
    renderAgentTasks();
    renderAgentAssignments();
    renderAgentHandlingSessions();
    syncFormsWithTask();
    text(
        "agentReplyTargetNote",
        state.selectedTaskId
            ? `Replies are added to the shared conversation and anchored to ${state.selectedTaskId}.`
            : "Replies are added to the shared conversation. The newest open task will be used if one exists."
    );
}

function renderExpertWorkspace() {
    const heading = el("expertTaskHeading");
    if (!heading) {
        return;
    }
    if (!state.currentTask) {
        clearWorkspace();
        return;
    }

    heading.textContent = `${state.currentTask.task_id} • ${state.currentTask.issue_type || "expert task"}`;
    const meta = el("expertTaskMeta");
    if (meta) {
        meta.innerHTML = [
            badge(state.currentTask.execution_tier || "EXPERT"),
            badge(state.currentTask.task_type || "EXPERT_TASK"),
            badge(state.currentTask.status),
            badge(state.currentTask.assigned_queue || "queue-triage"),
            state.currentTask.assigned_agent ? badge(`Owner ${state.currentTask.assigned_agent}`) : badge("Unassigned")
        ].join("");
    }
    const jtbdMeta = el("expertTaskJtbdMeta");
    if (jtbdMeta) {
        jtbdMeta.textContent = state.currentTask.customer_jtbd_id
            ? `Linked JTBD ${state.currentTask.customer_jtbd_type_name} • Stage ${state.currentTask.customer_jtbd_stage_name} • ${state.currentTask.customer_jtbd_status}`
            : "No JTBD linked to this expert task.";
    }
    const scopeNote = el("expertScopeNote");
    if (scopeNote) {
        scopeNote.textContent = state.expertMessageScope === "conversation"
            ? "You are seeing the broader customer conversation for additional context."
            : "Messages shown here are narrowed to the linked JTBD when possible.";
    }
    setScopeButtonState();
    text("expertDocumentCount", `${state.documents.length} docs`);
    renderChatThread("expertMessageTimeline", state.messages, false);
    renderDocumentsIn("expertDocumentList", "Relevant evidence and attachments appear here.");
}

function filteredAgentTasks() {
    if (state.view !== "agent") {
        return state.tasks;
    }
    if (state.agentDomainFilter === "ALL") {
        return state.tasks || [];
    }
    return (state.tasks || []).filter((task) => domainForTask(task) === state.agentDomainFilter);
}

function populateAgentCustomerFilter() {
    const select = el("agentCustomerFilter");
    if (!select || state.view !== "agent") {
        return;
    }
    const customers = (state.agentCustomers || []).map((customer) => customer.customer_id).sort();
    const current = state.agentCustomerFilter || "";
    select.innerHTML = `
        <option value="">Choose customer</option>
        ${customers.map((customerId) => `<option value="${escapeHtml(customerId)}">${escapeHtml(customerId)}</option>`).join("")}
    `;
    select.value = customers.includes(current) ? current : "";
    state.agentCustomerFilter = select.value;
}

function populateAgentDomainFilter() {
    const select = el("agentDomainFilter");
    if (!select) {
        return;
    }
    const domains = state.agentWorkspace?.domains || [];
    select.innerHTML = `
        <option value="ALL">All domains</option>
        ${domains.map((domain) => `<option value="${escapeHtml(domain)}">${escapeHtml(domain)}</option>`).join("")}
    `;
    select.value = domains.includes(state.agentDomainFilter) ? state.agentDomainFilter : "ALL";
    state.agentDomainFilter = select.value;
}

function renderAgentCustomerList() {
    const container = el("agentCustomerList");
    if (!container) {
        return;
    }
    const customers = state.agentCustomers || [];
    if (!customers.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No customers yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = customers.map((customer) => `
        <article class="task-card ${customer.customer_id === state.agentCustomerFilter ? "active" : ""}" data-agent-customer-id="${customer.customer_id}">
            <div class="bubble-meta">
                <strong>${escapeHtml(customer.customer_id)}</strong>
                <span class="badge">${customer.active_jtbd_count} active JTBDs</span>
            </div>
            <p class="task-supporting">${[...(customer.emails || []), ...(customer.phones || [])].join(" • ") || "No identifiers"}</p>
            <div class="bubble-meta">
                <span class="badge">${customer.task_count} tasks</span>
            </div>
        </article>
    `).join("");
    container.querySelectorAll("[data-agent-customer-id]").forEach((card) => {
        card.addEventListener("click", () => selectAgentCustomer(card.dataset.agentCustomerId).catch(handleError));
    });
}

function renderAgentJtbds() {
    const container = el("agentJtbdList");
    if (!container) {
        return;
    }
    const jtbds = filteredAgentJtbds();
    if (!jtbds.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No JTBDs on this customer yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = jtbds.map((jtbd) => `
        <article class="task-card mapping-card customer-jtbd-card">
            <div class="bubble-meta">
                <strong>${escapeHtml(jtbd.jtbd_type_name)}</strong>
                <span class="badge">${escapeHtml(jtbd.status)}</span>
            </div>
            <p class="task-supporting">${escapeHtml(jtbd.current_stage_name)} • ${escapeHtml(jtbd.public_id)}</p>
        </article>
    `).join("");
}

function renderAgentTasks() {
    const container = el("agentTaskList");
    if (!container) {
        return;
    }
    const tasks = filteredAgentTasks();
    if (!tasks.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No tasks on this customer yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = tasks.map(renderTaskCard).join("");
    container.querySelectorAll(".task-card").forEach((card) => {
        card.addEventListener("click", () => {
            persistTaskId(card.dataset.taskId);
            state.currentTask = (state.tasks || []).find((task) => task.task_id === card.dataset.taskId) || null;
            renderAgentWorkspace();
        });
    });
}

function renderAgentAssignments() {
    const container = el("agentAssignmentList");
    if (!container) {
        return;
    }
    const assignments = filteredAgentAssignments();
    if (!assignments.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No routing assignments yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = assignments.map((assignment) => `
        <article class="task-card mapping-card">
            <div class="bubble-meta">
                <strong>${escapeHtml(assignment.assigned_group)}</strong>
                <span class="badge">${escapeHtml(assignment.status)}</span>
            </div>
            <p class="task-supporting">${escapeHtml(assignment.assigned_agent || "Unassigned")} • ${formatDate(assignment.assigned_at)}</p>
        </article>
    `).join("");
}

function renderAgentHandlingSessions() {
    const container = el("agentHandlingSessionList");
    if (!container) {
        return;
    }
    const sessions = filteredAgentHandlingSessions();
    if (!sessions.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No handling sessions yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = sessions.map((session) => `
        <article class="task-card mapping-card">
            <div class="bubble-meta">
                <strong>${escapeHtml(session.assigned_group)}</strong>
                <span class="badge">${session.end_at ? "Ended" : "Active"}</span>
            </div>
            <p class="task-supporting">${escapeHtml(session.assigned_agent || "Unassigned")} • ${formatDate(session.start_at)}${session.end_at ? ` to ${formatDate(session.end_at)}` : ""}</p>
        </article>
    `).join("");
}

function renderCustomerExperience() {
    const customerHeading = el("customerAppHeading");
    if (!customerHeading) {
        return;
    }
    if (!state.currentRequest) {
        customerHeading.textContent = "Your support conversation";
        text("customerAppSubhead", "Your customer journey now stays in one continuous thread across app, email, and WhatsApp.");
        text("customerStatusPill", "Ready");
        renderChatThread("customerConversationThread", [], true);
        renderDocumentsIn("customerDocumentList", "Relevant documents will appear here.");
        text("customerDocumentCount", "0 docs");
        return;
    }

    customerHeading.textContent = state.currentRequest.title || "Selected request";
    text(
        "customerAppSubhead",
        "This view keeps your full customer conversation together while also showing the visible requests, progress, and relevant documents."
    );
    text("customerStatusPill", state.currentRequest.status_label || "Active");
    renderChatThread("customerConversationThread", state.messages, true);
    renderDocumentsIn("customerDocumentList", "Relevant documents will appear here.");
    text("customerDocumentCount", `${state.documents.length} docs`);
}

function renderCustomerTaskList() {
    const container = el("customerTaskList");
    if (!container) {
        return;
    }
    const jtbds = state.currentRequest?.jtbds || [];
    text("customerTaskCount", `${jtbds.length} JTBD${jtbds.length === 1 ? "" : "s"}`);
    if (!jtbds.length) {
        container.className = "queue-stack empty-state";
        container.textContent = "No visible JTBDs yet.";
        return;
    }
    container.className = "queue-stack";
    container.innerHTML = jtbds.map((jtbd) => renderCustomerJtbdCard(jtbd)).join("");
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
            ? "No conversation yet."
            : "Select a task to load the full conversation.";
        return;
    }

    container.className = `chat-thread ${customerView ? "customer-view" : "agent-view"}`;
    container.innerHTML = "";
    messages.forEach((message) => container.appendChild(buildMessageNode(message, customerView)));
}

function renderCustomerJtbdCard(jtbd) {
    const statusTone = jtbd.completed ? "good" : "warning";
    return `
        <article class="task-card mapping-card customer-jtbd-card">
            <div class="bubble-meta">
                <strong>${escapeHtml(jtbd.jtbd_type_name || "Request")}</strong>
                <span class="status-pill ${statusTone}">${escapeHtml(jtbd.completed ? "Completed" : "Active")}</span>
            </div>
            <div class="bubble-meta">
                <span class="badge">${escapeHtml(jtbd.stage_name || "Open")}</span>
                <span class="badge">${escapeHtml(jtbd.status || "ACTIVE")}</span>
            </div>
            <p class="task-supporting">${escapeHtml(jtbd.jtbd_id)}</p>
        </article>
    `;
}

function buildMessageNode(message, customerView) {
    const template = document.getElementById("messageTemplate");
    const node = template.content.firstElementChild.cloneNode(true);
    const senderClass = message.sender_type === "AGENT"
        ? "agent"
        : message.sender_type === "CUSTOMER"
            ? "customer"
            : message.sender_type === "EXPERT"
                ? "expert"
                : "system";
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
    renderDocumentsIn("documentList", "Documents shared from any channel appear here.");
}

function renderDocumentsIn(containerId, emptyCopy) {
    renderDocumentsFromList(containerId, state.documents, emptyCopy);
}

function renderDocumentsFromList(containerId, documents, emptyCopy) {
    const container = el(containerId);
    const template = document.getElementById("documentTemplate");
    if (!container || !template) {
        return;
    }
    if (!documents.length) {
        container.className = "document-list empty-state";
        container.textContent = emptyCopy;
        return;
    }

    container.className = "document-list";
    container.innerHTML = "";
    documents.forEach((documentItem) => {
        const node = template.content.firstElementChild.cloneNode(true);
        node.querySelector(".document-type").textContent = documentItem.document_type;
        node.querySelector(".document-meta").textContent =
            `${channelLabel(documentItem.source_channel)} • ${documentItem.customer_jtbd_type_name || "General"} • ${documentItem.policy_id || "No policy"} • ${documentItem.claim_id || "No claim"}`;
        const link = node.querySelector(".document-link");
        link.href = documentItem.file_url;
        container.appendChild(node);
    });
}

function syncFormsWithTask() {
    if (!state.currentTask) {
        setFormValue("#patchForm [name='status']", "");
        return;
    }
    setFormValue("#patchForm [name='status']", state.currentTask.status || "");
    setFormValue("#expertPatchForm [name='status']", state.currentTask.status || "");
    setFormValue("#expertPatchForm [name='assignedAgent']", state.currentTask.assigned_agent || "");
}

function clearWorkspace() {
    if (state.view === "customer") {
        state.currentRequest = null;
        state.messages = [];
        state.documents = [];
        persistRequestId(null);
        renderCustomerExperience();
        renderCustomerTaskList();
        return;
    }
    if (state.view === "expert") {
        text("expertTaskHeading", "Select an expert task");
        html("expertTaskMeta", "");
        text("expertTaskJtbdMeta", "");
        text("expertScopeNote", "Messages shown here are narrowed to the linked JTBD when possible.");
        renderChatThread("expertMessageTimeline", [], false);
        const documentList = el("expertDocumentList");
        if (documentList) {
            documentList.className = "document-list empty-state";
            documentList.textContent = "Relevant evidence and attachments appear here.";
        }
        state.currentTask = null;
        state.messages = [];
        state.documents = [];
        persistTaskId(null);
        return;
    }
    text("taskHeading", state.view === "agent" ? "Select a customer" : "Select a task");
    html("taskMeta", "");
    text("taskJtbdMeta", "");
    renderChatThread("messageTimeline", [], false);
    const documentList = el("documentList");
    if (documentList) {
        documentList.className = "document-list empty-state";
        documentList.textContent = "Documents shared from any channel appear here.";
    }
    if (state.view === "agent") {
        html("agentJtbdList", "No JTBDs on this customer yet.");
        html("agentTaskList", "No tasks on this customer yet.");
        html("agentAssignmentList", "No routing assignments yet.");
        html("agentHandlingSessionList", "No handling sessions yet.");
        text("timelineCount", "Select a customer to load the full conversation.");
        text("documentCount", "0 docs");
        text("agentReplyTargetNote", "Replies are added to the shared conversation and anchored to the selected task when one is available.");
        state.agentWorkspace = null;
    }
    state.currentTask = null;
    state.messages = [];
    state.documents = [];
    persistTaskId(null);
    renderCustomerExperience();
    renderCustomerTaskList();
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

function ensureAgentCustomerSelected() {
    if (!state.agentCustomerFilter) {
        throw new Error("Select a customer first.");
    }
}

async function submitCustomerComposeForm(event) {
    const data = new FormData(event.currentTarget);
    const body = data.get("body");
    const uploadedFiles = await uploadSelectedFiles(data.getAll("attachments"));
    let requestId = state.selectedRequestId;

    if (!requestId) {
        const created = await api("/v1/customers/me/requests", {
            method: "POST",
            body: {
                issue_type: "general_support_request",
                lob: null,
                claim_id: null,
                policy_id: null,
                priority: "MEDIUM",
                source_channel: "UI",
                initial_message_body: body,
                sender_identifier: state.user.email,
                initial_message_metadata: { source: "customer_conversation" },
                initial_external_thread_ref: `ui-${Date.now()}`
            }
        });
        requestId = created.data.request_id;
    }

    if (uploadedFiles.length) {
        for (let index = 0; index < uploadedFiles.length; index += 1) {
            const file = uploadedFiles[index];
            await api(`/v1/customers/me/requests/${encodeURIComponent(requestId)}/documents`, {
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
        await api(`/v1/customers/me/requests/${encodeURIComponent(requestId)}/messages`, {
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

    pushEvent("Customer conversation updated", "Your message and any documents were added to the same customer conversation.");
    await refreshBoard(requestId);
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
    text("expertIdentity", state.user.roles && state.user.roles.includes("ROLE_EXPERT")
        ? `Signed in as ${state.user.name || state.user.email}`
        : state.user.email);
}

function resolveAgentSelectedTask(workspace) {
    const current = state.selectedTaskId;
    if (current && (workspace.tasks || []).some((task) => task.task_id === current)) {
        return current;
    }
    return workspace.default_task_id || null;
}

function filteredAgentJtbds() {
    const all = state.agentWorkspace?.jtbds || [];
    if (state.agentDomainFilter === "ALL") {
        return all;
    }
    const jtbdIds = new Set(filteredAgentTasks().map((task) => task.customer_jtbd_id).filter(Boolean));
    return all.filter((jtbd) => jtbdIds.has(jtbd.public_id));
}

function filteredAgentMessages() {
    const all = state.agentWorkspace?.messages || [];
    if (state.agentDomainFilter === "ALL") {
        return all;
    }
    const taskIds = new Set(filteredAgentTasks().map((task) => task.task_id));
    const jtbdIds = new Set(filteredAgentJtbds().map((jtbd) => jtbd.public_id));
    return all.filter((message) => taskIds.has(message.task_id) || (message.customer_jtbd_id && jtbdIds.has(message.customer_jtbd_id)));
}

function filteredAgentDocuments() {
    const all = state.agentWorkspace?.documents || [];
    if (state.agentDomainFilter === "ALL") {
        return all;
    }
    const taskIds = new Set(filteredAgentTasks().map((task) => task.task_id));
    const jtbdIds = new Set(filteredAgentJtbds().map((jtbd) => jtbd.public_id));
    return all.filter((documentItem) => taskIds.has(documentItem.task_id) || (documentItem.customer_jtbd_id && jtbdIds.has(documentItem.customer_jtbd_id)));
}

function filteredAgentAssignments() {
    const all = state.agentWorkspace?.assignments || [];
    if (state.agentDomainFilter === "ALL") {
        return all;
    }
    return all.filter((assignment) => humanizeDomain(assignment.assigned_group) === state.agentDomainFilter);
}

function filteredAgentHandlingSessions() {
    const all = state.agentWorkspace?.handling_sessions || [];
    if (state.agentDomainFilter === "ALL") {
        return all;
    }
    return all.filter((session) => humanizeDomain(session.assigned_group) === state.agentDomainFilter);
}

function domainForTask(task) {
    return humanizeDomain(task.assigned_queue || task.lob || "General");
}

function humanizeDomain(value) {
    const normalized = `${value || ""}`
        .replace("queue-", "")
        .replaceAll("_", " ")
        .replaceAll("-", " ")
        .trim()
        .toLowerCase();
    if (!normalized) {
        return "General";
    }
    return normalized.replace(/\b\w/g, (match) => match.toUpperCase());
}

async function setExpertMessageScope(scope) {
    if (state.expertMessageScope === scope) {
        return;
    }
    state.expertMessageScope = scope;
    setScopeButtonState();
    if (state.view === "expert" && state.selectedTaskId) {
        await selectTask(state.selectedTaskId);
    }
}

function setScopeButtonState() {
    const relevant = el("expertScopeRelevant");
    const conversation = el("expertScopeConversation");
    if (relevant) {
        relevant.classList.toggle("active", state.expertMessageScope === "relevant");
    }
    if (conversation) {
        conversation.classList.toggle("active", state.expertMessageScope === "conversation");
    }
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
