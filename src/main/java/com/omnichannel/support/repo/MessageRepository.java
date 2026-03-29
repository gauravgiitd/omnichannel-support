package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.Ticket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByTicketOrderByCreatedAtAsc(Ticket ticket);

    List<Message> findByTicketIn(List<Ticket> tickets);

    Optional<Message> findByPublicId(String publicId);

    @EntityGraph(attributePaths = "ticket")
    Optional<Message> findByExternalThreadRef(String externalThreadRef);
}
