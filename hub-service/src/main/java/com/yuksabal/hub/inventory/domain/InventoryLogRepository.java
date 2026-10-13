package com.yuksabal.hub.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InventoryLogRepository extends JpaRepository<InventoryLog, UUID> {
}
