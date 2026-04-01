package com.omnichannel.support.service;

import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.HandlingSession;
import com.omnichannel.support.repo.HandlingSessionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HandlingSessionService {

    private final HandlingSessionRepository handlingSessionRepository;

    @Transactional
    public void startOrContinue(Conversation conversation, String assignedGroup, String assignedAgent) {
        if (conversation == null || assignedGroup == null || assignedGroup.isBlank()) {
            return;
        }
        HandlingSession current = handlingSessionRepository
                .findFirstByConversationAndEndAtIsNullOrderByStartAtDesc(conversation)
                .orElse(null);
        if (current != null
                && assignedGroup.equals(current.getAssignedGroup())
                && equalsNullable(assignedAgent, current.getAssignedAgent())) {
            return;
        }
        if (current != null && current.getEndAt() == null) {
            current.setEndAt(Instant.now());
            handlingSessionRepository.save(current);
        }
        HandlingSession session = new HandlingSession();
        session.setConversation(conversation);
        session.setAssignedGroup(assignedGroup);
        session.setAssignedAgent(assignedAgent);
        handlingSessionRepository.save(session);
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
