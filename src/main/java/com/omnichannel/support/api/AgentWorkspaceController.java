package com.omnichannel.support.api;

import com.omnichannel.support.dto.AgentCustomerWorkspaceDto;
import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.CreateExpertTaskRequest;
import com.omnichannel.support.dto.CreateCustomerJtbdRequest;
import com.omnichannel.support.dto.CustomerJtbdDto;
import com.omnichannel.support.dto.CustomerSummaryDto;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.JtbdTypeDto;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.RegisterDocumentRequest;
import com.omnichannel.support.dto.TaskDto;
import com.omnichannel.support.dto.WhatsAppCallActionRequest;
import com.omnichannel.support.dto.WhatsAppCallControlDto;
import com.omnichannel.support.dto.WhatsAppCallEventDto;
import com.omnichannel.support.security.AppUser;
import com.omnichannel.support.security.AuthenticatedUserService;
import com.omnichannel.support.service.AgentWorkspaceService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/agent")
@RequiredArgsConstructor
public class AgentWorkspaceController {

    private final AgentWorkspaceService agentWorkspaceService;
    private final AuthenticatedUserService authenticatedUserService;

    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<List<CustomerSummaryDto>>> listCustomers() {
        return ResponseEntity.ok(ApiResponse.success(agentWorkspaceService.listCustomers()));
    }

    @GetMapping("/jtbd-types")
    public ResponseEntity<ApiResponse<List<JtbdTypeDto>>> listJtbdTypes() {
        return ResponseEntity.ok(ApiResponse.success(agentWorkspaceService.listJtbdTypes()));
    }

    @GetMapping("/customers/{customerId}/workspace")
    public ResponseEntity<ApiResponse<AgentCustomerWorkspaceDto>> getWorkspace(
            @PathVariable("customerId") String customerId) {
        return ResponseEntity.ok(ApiResponse.success(agentWorkspaceService.getWorkspace(customerId)));
    }

    @PostMapping(path = "/customers/{customerId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MessageDto>> postMessage(
            @PathVariable("customerId") String customerId,
            @RequestParam(name = "taskId", required = false) String taskId,
            @Valid @RequestBody PostMessageRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        MessageDto message = agentWorkspaceService.postConversationMessage(customerId, user.email(), taskId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message));
    }

    @PostMapping(path = "/customers/{customerId}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<DocumentDto>> registerDocument(
            @PathVariable("customerId") String customerId,
            @RequestParam(name = "taskId", required = false) String taskId,
            @Valid @RequestBody RegisterDocumentRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        DocumentDto document =
                agentWorkspaceService.registerConversationDocument(customerId, user.email(), taskId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(document));
    }

    @GetMapping("/customers/{customerId}/tasks/{taskId}/messages")
    public ResponseEntity<ApiResponse<List<MessageDto>>> listInternalTaskMessages(
            @PathVariable("customerId") String customerId,
            @PathVariable("taskId") String taskId) {
        return ResponseEntity.ok(ApiResponse.success(agentWorkspaceService.listInternalTaskMessages(customerId, taskId)));
    }

    @GetMapping("/customers/{customerId}/tasks/{taskId}/documents")
    public ResponseEntity<ApiResponse<List<DocumentDto>>> listInternalTaskDocuments(
            @PathVariable("customerId") String customerId,
            @PathVariable("taskId") String taskId) {
        return ResponseEntity.ok(ApiResponse.success(agentWorkspaceService.listInternalTaskDocuments(customerId, taskId)));
    }

    @PostMapping(path = "/customers/{customerId}/tasks/{taskId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MessageDto>> postInternalTaskMessage(
            @PathVariable("customerId") String customerId,
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody PostMessageRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        MessageDto message =
                agentWorkspaceService.postInternalTaskMessage(customerId, user.email(), taskId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message));
    }

    @PostMapping(path = "/customers/{customerId}/tasks/{taskId}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<DocumentDto>> registerInternalTaskDocument(
            @PathVariable("customerId") String customerId,
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody RegisterDocumentRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        DocumentDto document =
                agentWorkspaceService.registerInternalTaskDocument(customerId, user.email(), taskId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(document));
    }

    @PostMapping(path = "/customers/{customerId}/expert-tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TaskDto>> createExpertTask(
            @PathVariable("customerId") String customerId,
            @Valid @RequestBody CreateExpertTaskRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        TaskDto task = agentWorkspaceService.createExpertTask(customerId, user.email(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(task));
    }

    @PostMapping(path = "/customers/{customerId}/jtbds", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<CustomerJtbdDto>> createConversationJtbd(
            @PathVariable("customerId") String customerId,
            @Valid @RequestBody CreateCustomerJtbdRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(agentWorkspaceService.createConversationJtbd(customerId, request)));
    }

    @PostMapping("/customers/{customerId}/jtbds/{customerJtbdId}/activate")
    public ResponseEntity<ApiResponse<CustomerJtbdDto>> activateConversationJtbd(
            @PathVariable("customerId") String customerId,
            @PathVariable("customerJtbdId") String customerJtbdId) {
        return ResponseEntity.ok(ApiResponse.success(
                agentWorkspaceService.activateConversationJtbd(customerId, customerJtbdId)));
    }

    @PostMapping("/customers/{customerId}/jtbds/{customerJtbdId}/deactivate")
    public ResponseEntity<ApiResponse<CustomerJtbdDto>> deactivateConversationJtbd(
            @PathVariable("customerId") String customerId,
            @PathVariable("customerJtbdId") String customerJtbdId) {
        return ResponseEntity.ok(ApiResponse.success(
                agentWorkspaceService.deactivateConversationJtbd(customerId, customerJtbdId)));
    }

    @PostMapping("/customers/{customerId}/jtbds/{customerJtbdId}/complete")
    public ResponseEntity<ApiResponse<CustomerJtbdDto>> completeConversationJtbd(
            @PathVariable("customerId") String customerId,
            @PathVariable("customerJtbdId") String customerJtbdId) {
        return ResponseEntity.ok(ApiResponse.success(
                agentWorkspaceService.completeConversationJtbd(customerId, customerJtbdId)));
    }

    @PostMapping("/customers/{customerId}/whatsapp-calls/permission")
    public ResponseEntity<ApiResponse<WhatsAppCallEventDto>> requestWhatsAppCallPermission(
            @PathVariable("customerId") String customerId,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(agentWorkspaceService.requestWhatsAppCallPermission(customerId, user.email())));
    }

    @GetMapping("/customers/{customerId}/whatsapp-calls/{callId}")
    public ResponseEntity<ApiResponse<WhatsAppCallControlDto>> getWhatsAppCall(
            @PathVariable("customerId") String customerId,
            @PathVariable("callId") String callId) {
        return ResponseEntity.ok(ApiResponse.success(agentWorkspaceService.getWhatsAppCall(customerId, callId)));
    }

    @PostMapping(path = "/customers/{customerId}/whatsapp-calls/{callId}/pre-accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<WhatsAppCallControlDto>> preAcceptWhatsAppCall(
            @PathVariable("customerId") String customerId,
            @PathVariable("callId") String callId,
            @Valid @RequestBody WhatsAppCallActionRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                agentWorkspaceService.preAcceptWhatsAppCall(customerId, callId, request.sdpType(), request.sdp(), user.email())));
    }

    @PostMapping(path = "/customers/{customerId}/whatsapp-calls/{callId}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<WhatsAppCallControlDto>> acceptWhatsAppCall(
            @PathVariable("customerId") String customerId,
            @PathVariable("callId") String callId,
            @Valid @RequestBody WhatsAppCallActionRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                agentWorkspaceService.acceptWhatsAppCall(customerId, callId, request.sdpType(), request.sdp(), user.email())));
    }

    @PostMapping(path = "/customers/{customerId}/whatsapp-calls/{callId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<WhatsAppCallControlDto>> rejectWhatsAppCall(
            @PathVariable("customerId") String customerId,
            @PathVariable("callId") String callId,
            @Valid @RequestBody WhatsAppCallActionRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                agentWorkspaceService.rejectWhatsAppCall(customerId, callId, user.email())));
    }

    @PostMapping(path = "/customers/{customerId}/whatsapp-calls/{callId}/terminate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<WhatsAppCallControlDto>> terminateWhatsAppCall(
            @PathVariable("customerId") String customerId,
            @PathVariable("callId") String callId,
            @Valid @RequestBody WhatsAppCallActionRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                agentWorkspaceService.terminateWhatsAppCall(customerId, callId, user.email())));
    }
}
