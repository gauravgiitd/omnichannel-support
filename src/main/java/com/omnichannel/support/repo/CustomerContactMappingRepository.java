package com.omnichannel.support.repo;

import com.omnichannel.support.domain.CustomerContactMapping;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerContactMappingRepository extends JpaRepository<CustomerContactMapping, Long> {

    Optional<CustomerContactMapping> findByEmail(String email);

    Optional<CustomerContactMapping> findByPhone(String phone);
}
