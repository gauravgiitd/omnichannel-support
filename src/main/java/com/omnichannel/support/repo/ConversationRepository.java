package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Conversation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByCustomerId(String customerId);

    Optional<Conversation> findByPublicId(String publicId);
}
