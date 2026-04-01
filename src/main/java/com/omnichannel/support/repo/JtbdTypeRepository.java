package com.omnichannel.support.repo;

import com.omnichannel.support.domain.JtbdType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JtbdTypeRepository extends JpaRepository<JtbdType, Long> {

    Optional<JtbdType> findByPublicId(String publicId);

    Optional<JtbdType> findByName(String name);
}
