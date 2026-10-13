package com.yuksabal.delivery.routelog.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeliveryRouteLogRepository extends JpaRepository<DeliveryRouteLog, UUID> {
}
