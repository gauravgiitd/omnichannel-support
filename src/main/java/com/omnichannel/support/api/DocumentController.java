package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.RegisterDocumentRequest;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
public class DocumentController {

    private final TaskService taskService;
    private final TaskAccessService taskAccessService;
    private final AuthenticatedUserService authenticatedUserService;

    @GetMapping("/{taskId}/documents")
    public ResponseEntity<ApiResponse<List<DocumentDto>>> listDocuments(
            @PathVariable("taskId") String taskId, Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        return ResponseEntity.ok(ApiResponse.success(taskService.listDocuments(taskId)));
    }

    @PostMapping(path = "/{taskId}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<DocumentDto>> registerDocument(
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody RegisterDocumentRequest request,
            Authentication authentication) {
        taskAccessService.assertCanAccessTask(authentication, taskId);
        RegisterDocumentRequest trustedRequest = request;
        if (!authenticatedUserService.isAgent(authentication)) {
            AppUser user = authenticatedUserService.requireCurrentUser(authentication);
            trustedRequest = new RegisterDocumentRequest(
                    request.channel(),
                    com.omnichannel.support.domain.SenderType.CUSTOMER,
                    user.email(),
                    request.fileUrl(),
                    request.documentType(),
                    request.claimId(),
                    request.policyId(),
                    request.messageBody(),
                    request.metadata());
        }
        DocumentDto created = taskService.registerDocument(taskId, trustedRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }
}
