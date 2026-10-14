package com.yuksabal.delivery.delivery.repository;

import com.yuksabal.delivery.delivery.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;


public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    Optional<Delivery> findByOrderId(UUID orderId);
}
