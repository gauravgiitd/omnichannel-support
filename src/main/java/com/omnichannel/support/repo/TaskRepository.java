package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {

    @EntityGraph(attributePaths = {"conversation", "customerJtbd", "customerJtbd.jtbdType", "customerJtbd.currentStage"})
    Optional<Task> findByTaskNumber(String taskNumber);

    @EntityGraph(attributePaths = {"conversation", "customerJtbd", "customerJtbd.jtbdType", "customerJtbd.currentStage"})
    List<Task> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    @EntityGraph(attributePaths = {"conversation", "customerJtbd", "customerJtbd.jtbdType", "customerJtbd.currentStage"})
    List<Task> findByCustomerIdAndStatusInOrderByCreatedAtDesc(
            String customerId, Collection<TaskStatus> statuses);

    @EntityGraph(attributePaths = {"conversation", "customerJtbd", "customerJtbd.jtbdType", "customerJtbd.currentStage"})
    List<Task> findByCustomerJtbdIdAndStatusInOrderByCreatedAtDesc(
            Long customerJtbdId, Collection<TaskStatus> statuses);

    @EntityGraph(attributePaths = {"conversation", "customerJtbd", "customerJtbd.jtbdType", "customerJtbd.currentStage"})
    List<Task> findByCustomerJtbdIdOrderByCreatedAtDesc(Long customerJtbdId);
}
