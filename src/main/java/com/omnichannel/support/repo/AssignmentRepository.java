package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Assignment;
import com.omnichannel.support.domain.AssignmentStatus;
import com.omnichannel.support.domain.Conversation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    List<Assignment> findByConversationOrderByAssignedAtDesc(Conversation conversation);

    Optional<Assignment> findFirstByConversationAndStatusOrderByAssignedAtDesc(
            Conversation conversation, AssignmentStatus status);
}
