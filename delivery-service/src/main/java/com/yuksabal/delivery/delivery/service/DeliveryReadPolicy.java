package com.yuksabal.delivery.delivery.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.global.exception.DeliveryErrorCode;
import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import com.yuksabal.delivery.routelog.repository.DeliveryRouteLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

// 배송 상세·경로 기록 조회 권한. 배송 서비스와 경로 기록 서비스가 같이 쓴다
// 목록 조건(DeliverySpecification.readableBy)과 같은 규칙
@Component
@RequiredArgsConstructor
public class DeliveryReadPolicy {

    private final DeliveryRouteLogRepository deliveryRouteLogRepository;

    // 배송 상세: 경로 기록은 DELIVERY가 배송 담당자도 주문자도 아닐 때만 조회한다
    public void checkReadable(Delivery delivery, UUID userId, UserRole role) {
        checkReadable(delivery, () -> deliveryRouteLogRepository.findByDeliveryId(delivery.getId()), userId, role);
    }

    // 경로 기록 조회: 이미 조회한 경로 기록을 쓴다
    public void checkReadable(Delivery delivery, Optional<DeliveryRouteLog> routeLog, UUID userId, UserRole role) {
        checkReadable(delivery, () -> routeLog, userId, role);
    }

    // MASTER는 전체, 그 외에는 주문자 본인 (order 주문 조회와 같은 기준, 모든 역할이 주문할 수 있다)
    // DELIVERY는 배송(허브→업체) 담당자 또는 경로 기록(허브→허브) 담당자도. 경로 기록 조회가 가장 비싸서 마지막에 둔다
    private void checkReadable(Delivery delivery, Supplier<Optional<DeliveryRouteLog>> routeLog,
                               UUID userId, UserRole role) {
        boolean readable = switch (role) {
            case MASTER -> true;
            case DELIVERY -> userId.equals(delivery.getDeliveryManagerId())
                    || delivery.isOrderedBy(userId)
                    || routeLog.get().map(log -> userId.equals(log.getDeliveryManagerId())).orElse(false);
            case COMPANY, USER -> delivery.isOrderedBy(userId);
        };
        if (!readable) {
            throw new BusinessException(DeliveryErrorCode.DELIVERY_ACCESS_DENIED,
                    "MASTER, 담당 배송 기사(배송·경로 기록), 주문자 본인만 조회할 수 있습니다.");
        }
    }
}
