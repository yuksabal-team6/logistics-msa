package com.yuksabal.hub.inventory.domain;

// inventory_change_type. DECREASE = 주문 차감, RESTORE = 주문 실패 복원, INBOUND = 입고
public enum InventoryChangeType {
    DECREASE, RESTORE, INBOUND
}
