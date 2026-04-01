package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.TaskDto;
import com.omnichannel.support.security.AppUser;
import com.omnichannel.support.security.AuthenticatedUserService;
import com.omnichannel.support.security.TaskAccessService;
import com.omnichannel.support.service.TaskService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/customers")
@RequiredArgsConstructor
public class CustomerTaskController {

    private final TaskService taskService;
    private final AuthenticatedUserService authenticatedUserService;
    private final TaskAccessService taskAccessService;

    @GetMapping("/me/tasks")
    public ResponseEntity<ApiResponse<List<TaskDto>>> listMyTasks(Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(taskService.listTasksForCustomer(user.customerId())));
    }

    @GetMapping("/{customerId}/tasks")
    public ResponseEntity<ApiResponse<List<TaskDto>>> listTasks(
            @PathVariable("customerId") String customerId, Authentication authentication) {
        taskAccessService.assertCanAccessCustomer(authentication, customerId);
        return ResponseEntity.ok(ApiResponse.success(taskService.listTasksForCustomer(customerId)));
    }
}
