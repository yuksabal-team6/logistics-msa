package com.yuksabal.delivery.delivery.repository;

import com.yuksabal.delivery.delivery.dto.request.DeliverySearchRequest;
import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.delivery.entity.DeliveryStatus;
import com.yuksabal.delivery.global.auth.UserRole;
import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

// 배송 목록 검색 조건. 값이 없으면 null을 반환해 조건에서 빠진다.
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DeliverySpecification {

    // DELIVERY는 검색 조건과 별개로 본인 담당 배송만 조회한다.
    public static Specification<Delivery> search(DeliverySearchRequest request, UUID userId, UserRole role) {
        return Specification
                .where(deliveryStatusEquals(request.deliveryStatus()))
                .and(departureHubIdEquals(request.departureHubId()))
                .and(destinationHubIdEquals(request.destinationHubId()))
                .and(managedBy(request.deliveryManagerId()))
                .and(managedBy(role == UserRole.DELIVERY ? userId : null));
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
