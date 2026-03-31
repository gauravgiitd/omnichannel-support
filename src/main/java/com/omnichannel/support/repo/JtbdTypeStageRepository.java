package com.omnichannel.support.repo;

import com.omnichannel.support.domain.JtbdType;
import com.omnichannel.support.domain.JtbdTypeStage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JtbdTypeStageRepository extends JpaRepository<JtbdTypeStage, Long> {

    List<JtbdTypeStage> findByJtbdTypeOrderByStageOrderAsc(JtbdType jtbdType);

    Optional<JtbdTypeStage> findByJtbdTypeAndStageKey(JtbdType jtbdType, String stageKey);
}
