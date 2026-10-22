package com.yuksabal.delivery.delivery.domain;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.delivery.delivery.presentation.dto.request.DeliverySearchRequest;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

// 배송 목록 검색 조건. 값이 없으면 null을 반환해 조건에서 빠진다.
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DeliverySpecification {

    // 검색 조건과 별개로 조회 권한이 있는 배송만 조회한다.
    public static Specification<Delivery> search(DeliverySearchRequest request, UUID userId, UserRole role) {
        return Specification
                .where(deliveryStatusEquals(request.deliveryStatus()))
                .and(departureHubIdEquals(request.departureHubId()))
                .and(destinationHubIdEquals(request.destinationHubId()))
                .and(managedBy(request.deliveryManagerId()))
                .and(readableBy(userId, role));
    }

    // 조회 권한. 상세·경로 기록 조회의 DeliveryReadPolicy와 같은 규칙
    // MASTER는 전체, 그 외에는 주문자 본인. DELIVERY는 본인 담당 배송도 (모든 역할이 주문할 수 있다)
    public static Specification<Delivery> readableBy(UUID userId, UserRole role) {
        return switch (role) {
            case MASTER -> (root, query, cb) -> null;
            case DELIVERY -> managedBy(userId).or(ordererIdEquals(userId));
            case COMPANY, USER -> ordererIdEquals(userId);
        };
    }

    // ordererId가 없는 배송(필드 추가 전)은 equal에 걸리지 않아 빠진다
    public static Specification<Delivery> ordererIdEquals(UUID ordererId) {
        return (root, query, cb) ->
                ordererId == null
                        ? null
                        : cb.equal(root.get("ordererId"), ordererId);
    }

    public static Specification<Delivery> deliveryStatusEquals(DeliveryStatus deliveryStatus) {
        return (root, query, cb) ->
                deliveryStatus == null
                        ? null
                        : cb.equal(root.get("deliveryStatus"), deliveryStatus);
    }

    public static Specification<Delivery> departureHubIdEquals(UUID departureHubId) {
        return (root, query, cb) ->
                departureHubId == null
                        ? null
                        : cb.equal(root.get("departureHubId"), departureHubId);
    }

    public static Specification<Delivery> destinationHubIdEquals(UUID destinationHubId) {
        return (root, query, cb) ->
                destinationHubId == null
                        ? null
                        : cb.equal(root.get("destinationHubId"), destinationHubId);
    }

    // 배송(허브→업체) 담당자 또는 경로 기록(허브→허브) 담당자
    // Delivery에서 경로 기록으로 가는 연관이 없어 EXISTS 서브쿼리로 확인한다.
    public static Specification<Delivery> managedBy(UUID deliveryManagerId) {
        return (root, query, cb) -> {
            if (deliveryManagerId == null) {
                return null;
            }
            Subquery<Integer> routeLog = query.subquery(Integer.class);
            Root<DeliveryRouteLog> log = routeLog.from(DeliveryRouteLog.class);
            routeLog.select(cb.literal(1))
                    .where(
                            cb.equal(log.get("delivery"), root),
                            cb.equal(log.get("deliveryManagerId"), deliveryManagerId));

            return cb.or(
                    cb.equal(root.get("deliveryManagerId"), deliveryManagerId),
                    cb.exists(routeLog));
        };
    }
}
