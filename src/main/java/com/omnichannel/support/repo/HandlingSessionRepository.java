package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.HandlingSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HandlingSessionRepository extends JpaRepository<HandlingSession, Long> {

    List<HandlingSession> findByConversationOrderByStartAtDesc(Conversation conversation);

    Optional<HandlingSession> findFirstByConversationAndEndAtIsNullOrderByStartAtDesc(Conversation conversation);
}
