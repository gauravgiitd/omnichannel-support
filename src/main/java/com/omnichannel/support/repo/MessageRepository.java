package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.Task;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByTaskOrderByCreatedAtAsc(Task task);

    List<Message> findByTaskIn(List<Task> tasks);

    Optional<Message> findByPublicId(String publicId);

    @EntityGraph(attributePaths = "task")
    Optional<Message> findByExternalThreadRef(String externalThreadRef);
}
