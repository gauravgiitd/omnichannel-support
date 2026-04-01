package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskMergeMap;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskMergeMapRepository extends JpaRepository<TaskMergeMap, Long> {

    Optional<TaskMergeMap> findByMergedTask(Task mergedTask);

    List<TaskMergeMap> findByPrimaryTaskInOrMergedTaskIn(List<Task> primaryTasks, List<Task> mergedTasks);

    boolean existsByMergedTask(Task mergedTask);
}
