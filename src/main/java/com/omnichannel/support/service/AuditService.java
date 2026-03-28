package com.omnichannel.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.domain.AuditLogEntry;
import com.omnichannel.support.repo.AuditLogRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(
            String action,
            String entityType,
            String entityId,
            String actorType,
            String actorId,
            Map<String, Object> payload) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setActorType(actorType);
        entry.setActorId(actorId);
        if (payload != null && !payload.isEmpty()) {
            try {
                entry.setPayloadJson(objectMapper.writeValueAsString(payload));
            } catch (JsonProcessingException e) {
                entry.setPayloadJson("{\"error\":\"serialization_failed\"}");
            }
        }
        auditLogRepository.save(entry);
    }
}
