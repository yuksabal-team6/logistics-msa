package com.yuksabal.delivery.delivery.repository;

import com.yuksabal.delivery.delivery.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;


public interface DeliveryRepository extends JpaRepository<Delivery, UUID>,
        JpaSpecificationExecutor<Delivery> {

    Optional<Delivery> findByOrderId(UUID orderId);
}
