package com.omnichannel.support.security;

import com.omnichannel.support.domain.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskAccessService {

    private final AuthenticatedUserService authenticatedUserService;
    private final com.omnichannel.support.service.TaskService taskService;

    public void assertCanAccessTask(Authentication authentication, String taskNumber) {
        if (authenticatedUserService.isAgent(authentication)) {
            return;
        }
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        Task task = taskService.loadCanonicalTask(taskNumber);
        if (!task.getCustomerId().equals(user.customerId())) {
            throw new AccessDeniedException("You do not have access to this task");
        }
    }

    public void assertCanAccessCustomer(Authentication authentication, String customerId) {
        if (authenticatedUserService.isAgent(authentication)) {
            return;
        }
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        if (!user.customerId().equals(customerId)) {
            throw new AccessDeniedException("You do not have access to this customer");
        }
    }
}
