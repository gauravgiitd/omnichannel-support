package com.omnichannel.support.service;

import com.omnichannel.support.domain.Assignment;
import com.omnichannel.support.domain.AssignmentStatus;
import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.Message;
import com.omnichannel.support.repo.AssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final HandlingSessionService handlingSessionService;

    @Transactional
    public Assignment assign(Conversation conversation, Message message, String assignedGroup, String assignedAgent) {
        if (conversation == null || assignedGroup == null || assignedGroup.isBlank()) {
            return null;
        }
        Assignment current = assignmentRepository
                .findFirstByConversationAndStatusOrderByAssignedAtDesc(conversation, AssignmentStatus.OPEN)
                .orElse(null);
        if (current != null
                && assignedGroup.equals(current.getAssignedGroup())
                && equalsNullable(assignedAgent, current.getAssignedAgent())) {
            current.setMessage(message);
            current.setStatus(AssignmentStatus.ACTIVE);
            Assignment saved = assignmentRepository.save(current);
            handlingSessionService.startOrContinue(conversation, assignedGroup, assignedAgent);
            return saved;
        }
        if (current != null && current.getClosedAt() == null) {
            current.setStatus(AssignmentStatus.CLOSED);
            current.setClosedAt(java.time.Instant.now());
            assignmentRepository.save(current);
        }
        Assignment assignment = new Assignment();
        assignment.setConversation(conversation);
        assignment.setMessage(message);
        assignment.setAssignedGroup(assignedGroup);
        assignment.setAssignedAgent(assignedAgent);
        assignment.setStatus(AssignmentStatus.OPEN);
        Assignment saved = assignmentRepository.save(assignment);
        handlingSessionService.startOrContinue(conversation, assignedGroup, assignedAgent);
        return saved;
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
