package com.yuksabal.delivery.delivery.domain;

// delivery_status. 같은 허브 배송은 READY → DELIVERING → COMPLETED (결정 #16)
public enum DeliveryStatus {
    READY, IN_TRANSIT, ARRIVED_AT_HUB, DELIVERING, COMPLETED, CANCELLED
}
