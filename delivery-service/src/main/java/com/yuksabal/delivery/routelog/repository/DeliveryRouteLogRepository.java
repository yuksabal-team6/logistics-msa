package com.yuksabal.delivery.routelog.repository;

import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryRouteLogRepository extends JpaRepository<DeliveryRouteLog, UUID> {

    Optional<DeliveryRouteLog> findByDeliveryId(UUID deliveryId);
}
