package com.omnichannel.support.repo;

import com.omnichannel.support.domain.AuditLogEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {

    List<AuditLogEntry> findTop100ByActionOrderByCreatedAtDesc(String action);
}
