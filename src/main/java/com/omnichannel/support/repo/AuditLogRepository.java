package com.omnichannel.support.repo;

import com.omnichannel.support.domain.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {}
