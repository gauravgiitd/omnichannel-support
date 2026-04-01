package com.omnichannel.support.service;

import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskMergeMap;
import com.omnichannel.support.domain.TaskStatus;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.MessageRepository;
import com.omnichannel.support.repo.TaskMergeMapRepository;
import com.omnichannel.support.repo.TaskRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskMergeService {

    private final TaskRepository taskRepository;
    private final MessageRepository messageRepository;
    private final TaskMergeMapRepository mergeMapRepository;
    private final TaskResolutionService taskResolutionService;
    private final AuditService auditService;

    @Transactional
    public void mergeTasks(String primaryNumber, String mergedNumber, String mergedByActor) {
        Task primary =
                taskRepository
                        .findByTaskNumber(primaryNumber)
                        .map(taskResolutionService::resolveCanonical)
                        .orElseThrow(() -> new NotFoundException("primary task not found"));
        Task merged =
                taskRepository
                        .findByTaskNumber(mergedNumber)
                        .map(taskResolutionService::resolveCanonical)
                        .orElseThrow(() -> new NotFoundException("merged task not found"));

        if (primary.getId().equals(merged.getId())) {
            throw new ValidationException("cannot merge a task into itself");
        }
        if (mergeMapRepository.findByMergedTask(merged).isPresent()) {
            throw new ValidationException("merged task is already merged into another task");
        }

        List<Message> messages = messageRepository.findByTaskOrderByCreatedAtAsc(merged);
        for (Message message : messages) {
            message.setTask(primary);
        }
        messageRepository.saveAll(messages);

        TaskMergeMap map = new TaskMergeMap();
        map.setPrimaryTask(primary);
        map.setMergedTask(merged);
        map.setMergedByActor(mergedByActor);
        mergeMapRepository.save(map);

        merged.setStatus(TaskStatus.CLOSED);
        taskRepository.save(merged);

        auditService.record(
                "TICKET_MERGED",
                "Task",
                primary.getTaskNumber(),
                "AGENT",
                mergedByActor,
                java.util.Map.of(
                        "primary_task", primary.getTaskNumber(),
                        "merged_task", merged.getTaskNumber()));
    }
}
