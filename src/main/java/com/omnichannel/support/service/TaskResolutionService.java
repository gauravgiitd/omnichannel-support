package com.omnichannel.support.service;

import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskMergeMap;
import com.omnichannel.support.repo.TaskMergeMapRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskResolutionService {

    private final TaskMergeMapRepository mergeMapRepository;

    public Task resolveCanonical(Task task) {
        Task current = task;
        while (true) {
            java.util.Optional<TaskMergeMap> merge = mergeMapRepository.findByMergedTask(current);
            if (merge.isEmpty()) {
                return current;
            }
            current = merge.get().getPrimaryTask();
        }
    }
}
