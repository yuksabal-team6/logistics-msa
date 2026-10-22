package com.yuksabal.hub.route.domain;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

// 허브 경로 목록 검색 조건. 값이 없으면 null을 반환해 조건에서 빠진다.
// 출발·도착 허브는 ID 컬럼이라 허브 테이블과 조인하지 않는다.
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
                        : cb.equal(root.get("departureHubId"), departureHubId);
    }

    public static Specification<HubRoute> arrivalHubIdEquals(UUID arrivalHubId) {
        return (root, query, cb) ->
                arrivalHubId == null
                        ? null
                        : cb.equal(root.get("arrivalHubId"), arrivalHubId);
    }
}
