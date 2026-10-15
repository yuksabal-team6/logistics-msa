package com.yuksabal.hub.inventory.repository;

import com.yuksabal.hub.inventory.entity.InventoryLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InventoryLogRepository extends JpaRepository<InventoryLog, UUID> {
}
