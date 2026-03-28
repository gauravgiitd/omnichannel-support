package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.Ticket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByTicketOrderByCreatedAtAsc(Ticket ticket);

    Optional<Message> findByPublicId(String publicId);

    Optional<Message> findByExternalThreadRef(String externalThreadRef);
}
