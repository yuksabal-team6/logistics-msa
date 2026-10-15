package com.yuksabal.hub.route.repository;

import com.yuksabal.hub.route.entity.HubRoute;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

// 허브 경로 목록 검색 조건. 값이 없으면 null을 반환해 조건에서 빠진다.
// 이미 departureHub.id는 FK 컬럼이라 허브 테이블과 조인하지 않는다.
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HubRouteSpecification {

    // 조건 없으면 전체 / 출발만 / 도착만 / 둘 다(최대 1건)
    public static Specification<HubRoute> search(UUID departureHubId, UUID arrivalHubId) {
        return Specification
                .where(departureHubIdEquals(departureHubId))
                .and(arrivalHubIdEquals(arrivalHubId));
    }

    public static Specification<HubRoute> departureHubIdEquals(UUID departureHubId) {
        return (root, query, cb) ->
                departureHubId == null
                        ? null
                        : cb.equal(root.get("departureHub").get("id"), departureHubId);
    }

    public static Specification<HubRoute> arrivalHubIdEquals(UUID arrivalHubId) {
        return (root, query, cb) ->
                arrivalHubId == null
                        ? null
                        : cb.equal(root.get("arrivalHub").get("id"), arrivalHubId);
    }
}
