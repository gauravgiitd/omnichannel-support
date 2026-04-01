package com.omnichannel.support.security;

import com.omnichannel.support.domain.ExecutionTier;
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
        if (authenticatedUserService.isAgent(authentication) || authenticatedUserService.isAdmin(authentication)) {
            return;
        }
        Task task = taskService.loadCanonicalTask(taskNumber);
        if (authenticatedUserService.isExpert(authentication)) {
            AppUser user = authenticatedUserService.requireCurrentUser(authentication);
            if (task.getExecutionTier() != ExecutionTier.EXPERT) {
                throw new AccessDeniedException("You do not have access to this task");
            }
            if (task.getAssignedAgent() != null
                    && !task.getAssignedAgent().isBlank()
                    && !task.getAssignedAgent().equalsIgnoreCase(user.email())) {
                throw new AccessDeniedException("You do not have access to this expert task");
            }
            return;
        }
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        if (!task.getCustomerId().equals(user.customerId())) {
            throw new AccessDeniedException("You do not have access to this task");
        }
    }

    public void assertCanAccessCustomer(Authentication authentication, String customerId) {
        if (authenticatedUserService.isStaff(authentication)) {
            return;
        }
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        if (!user.customerId().equals(customerId)) {
            throw new AccessDeniedException("You do not have access to this customer");
        }
    }
}
