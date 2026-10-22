package com.yuksabal.hub.route.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface HubRouteRepository extends
        JpaRepository<HubRoute, UUID>,
        JpaSpecificationExecutor<HubRoute> {

    // 출발·도착 허브 ID로 경로 한 건 (삭제된 경로는 @SQLRestriction으로 제외)
    Optional<HubRoute> findByDepartureHubIdAndArrivalHubId(UUID departureHubId, UUID arrivalHubId);
}
