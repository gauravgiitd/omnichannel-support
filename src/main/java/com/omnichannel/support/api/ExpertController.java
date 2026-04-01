package com.omnichannel.support.api;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PatchTaskRequest;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.RegisterDocumentRequest;
import com.omnichannel.support.dto.TaskDto;
import com.omnichannel.support.security.AppUser;
import com.omnichannel.support.security.AuthenticatedUserService;
import com.omnichannel.support.security.TaskAccessService;
import com.omnichannel.support.service.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/expert")
@RequiredArgsConstructor
public class ExpertController {

    private final TaskService taskService;
    private final TaskAccessService taskAccessService;
    private final AuthenticatedUserService authenticatedUserService;

    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<List<TaskDto>>> listTasks(Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        if (authenticatedUserService.isAdmin(authentication)) {
            return ResponseEntity.ok(ApiResponse.success(taskService.listExpertTasks(null, true)));
        }
        return ResponseEntity.ok(ApiResponse.success(taskService.listExpertTasks(user.email(), true)));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<ApiResponse<TaskDto>> getTask(
            @PathVariable("taskId") String taskId, Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        return ResponseEntity.ok(ApiResponse.success(taskService.getTask(taskId)));
    }

    @GetMapping("/tasks/{taskId}/messages")
    public ResponseEntity<ApiResponse<List<MessageDto>>> listMessages(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "scope", defaultValue = "relevant") String scope,
            Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        List<MessageDto> messages = "conversation".equalsIgnoreCase(scope)
                ? taskService.listMessages(taskId)
                : taskService.listRelevantMessages(taskId);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    @PostMapping(path = "/tasks/{taskId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MessageDto>> postMessage(
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody PostMessageRequest request,
            Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        PostMessageRequest trusted = new PostMessageRequest(
                request.channel() != null ? request.channel() : ChannelType.UI,
                SenderType.EXPERT,
                user.email(),
                request.body(),
                request.attachmentUrls(),
                request.externalThreadRef(),
                request.metadata());
        MessageDto message = taskService.postMessage(taskId, trusted);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message));
    }

    @GetMapping("/tasks/{taskId}/documents")
    public ResponseEntity<ApiResponse<List<DocumentDto>>> listDocuments(
            @PathVariable("taskId") String taskId, Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        return ResponseEntity.ok(ApiResponse.success(taskService.listDocuments(taskId)));
    }

    @PostMapping(path = "/tasks/{taskId}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<DocumentDto>> registerDocument(
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody RegisterDocumentRequest request,
            Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        RegisterDocumentRequest trusted = new RegisterDocumentRequest(
                request.channel() != null ? request.channel() : ChannelType.UI,
                SenderType.EXPERT,
                user.email(),
                request.fileUrl(),
                request.documentType(),
                request.claimId(),
                request.policyId(),
                request.messageBody(),
                request.metadata());
        DocumentDto created = taskService.registerDocument(taskId, trusted);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }

    @PatchMapping(path = "/tasks/{taskId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TaskDto>> patchTask(
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody PatchTaskRequest request,
            Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        return ResponseEntity.ok(ApiResponse.success(taskService.patchTask(taskId, request)));
    }
}
