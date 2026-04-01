package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.CreateAuthenticatedTaskRequest;
import com.omnichannel.support.dto.CreateTaskRequest;
import com.omnichannel.support.dto.MergeTasksRequest;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PatchTaskRequest;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.TaskDto;
import com.omnichannel.support.security.AppUser;
import com.omnichannel.support.security.AuthenticatedUserService;
import com.omnichannel.support.security.TaskAccessService;
import com.omnichannel.support.service.TaskMergeService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskMergeService taskMergeService;
    private final TaskAccessService taskAccessService;
    private final AuthenticatedUserService authenticatedUserService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TaskDto>>> listTasks() {
        return ResponseEntity.ok(ApiResponse.success(taskService.listAllTasks()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TaskDto>> createTask(@Valid @RequestBody CreateTaskRequest request) {
        TaskDto created = taskService.createTask(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }

    @PostMapping(path = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TaskDto>> createMyTask(
            @Valid @RequestBody CreateAuthenticatedTaskRequest request, Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        CreateTaskRequest trustedRequest = new CreateTaskRequest(
                user.customerId(),
                request.issueType(),
                request.lob(),
                request.claimId(),
                request.policyId(),
                request.priority(),
                request.sourceChannel(),
                request.initialMessageBody(),
                user.email(),
                request.initialMessageMetadata(),
                request.initialExternalThreadRef());
        TaskDto created = taskService.createTask(trustedRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<TaskDto>> getTask(
            @PathVariable("taskId") String taskId, Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        return ResponseEntity.ok(ApiResponse.success(taskService.getTask(taskId)));
    }

    @PatchMapping(path = "/{taskId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TaskDto>> patchTask(
            @PathVariable("taskId") String taskId, @Valid @RequestBody PatchTaskRequest request) {
        return ResponseEntity.ok(ApiResponse.success(taskService.patchTask(taskId, request)));
    }

    @GetMapping("/{taskId}/messages")
    public ResponseEntity<ApiResponse<List<MessageDto>>> listMessages(
            @PathVariable("taskId") String taskId, Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        return ResponseEntity.ok(ApiResponse.success(taskService.listMessages(taskId)));
    }

    @PostMapping(path = "/{taskId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MessageDto>> postMessage(
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody PostMessageRequest request,
            Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        PostMessageRequest trustedRequest = request;
        if (!authenticatedUserService.isAgent(authentication)) {
            AppUser user = authenticatedUserService.requireCurrentUser(authentication);
            trustedRequest = new PostMessageRequest(
                    request.channel(),
                    com.omnichannel.support.domain.SenderType.CUSTOMER,
                    user.email(),
                    request.body(),
                    request.attachmentUrls(),
                    request.externalThreadRef(),
                    request.metadata());
        }
        MessageDto message = taskService.postMessage(taskId, trustedRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message));
    }

    @PostMapping(path = "/merge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Void>> mergeTasks(@Valid @RequestBody MergeTasksRequest request) {
        taskMergeService.mergeTasks(
                request.primaryTaskNumber(), request.mergedTaskNumber(), request.mergedByActor());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
