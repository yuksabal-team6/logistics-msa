package com.yuksabal.hub.route.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface HubRouteRepository extends JpaRepository<HubRoute, UUID> {
}
