package com.yuksabal.delivery.routelog.repository;

import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import com.yuksabal.delivery.routelog.entity.RouteStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryRouteLogRepository extends JpaRepository<DeliveryRouteLog, UUID> {

    Optional<DeliveryRouteLog> findByDeliveryId(UUID deliveryId);

    // 경로 기록(허브→허브) 담당자로 진행 중인 경로 기록이 있는지. 삭제된 경로 기록은 @SQLRestriction으로 제외
    boolean existsByDeliveryManagerIdAndRouteStatusIn(UUID deliveryManagerId, Collection<RouteStatus> statuses);
}
