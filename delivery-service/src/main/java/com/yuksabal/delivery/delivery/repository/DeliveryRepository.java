package com.yuksabal.delivery.delivery.repository;

import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.delivery.entity.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;


public interface DeliveryRepository extends JpaRepository<Delivery, UUID>,
        JpaSpecificationExecutor<Delivery> {

    Optional<Delivery> findByOrderId(UUID orderId);

    // 배송(허브→업체) 담당자로 진행 중인 배송이 있는지. 삭제된 배송은 @SQLRestriction으로 제외
    boolean existsByDeliveryManagerIdAndDeliveryStatusIn(UUID deliveryManagerId, Collection<DeliveryStatus> statuses);
}
