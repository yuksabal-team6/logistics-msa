package com.yuksabal.hub.hub.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface HubRepository extends
        JpaRepository<Hub, UUID>,
        JpaSpecificationExecutor<Hub> {
}
