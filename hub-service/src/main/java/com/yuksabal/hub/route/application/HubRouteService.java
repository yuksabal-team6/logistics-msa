package com.yuksabal.hub.route.application;

import com.yuksabal.hub.global.exception.HubExceptions;
import com.yuksabal.hub.route.application.dto.HubRouteResult;
import com.yuksabal.hub.route.application.dto.HubRouteSearchCondition;
import com.yuksabal.hub.route.domain.HubRoute;
import com.yuksabal.hub.route.domain.HubRouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static com.yuksabal.common.util.PageableUtils.toStablePageable;
import static com.yuksabal.hub.route.domain.HubRouteSpecification.search;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HubRouteService {

    // 목록 정렬 허용 필드 (API 명세 0.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "distanceKm", "durationMinutes");

    private final HubRouteRepository hubRouteRepository;

    // 같은 허브끼리는 경로가 없고(결정 #16), 없는 허브 ID도 경로가 없으므로 모두 404로 응답한다.
    public HubRouteResult getHubRoute(UUID from, UUID to) {
        HubRoute hubRoute = hubRouteRepository.findByDepartureHubIdAndArrivalHubId(from, to)
                .orElseThrow(HubExceptions::hubRouteNotFound);
        return HubRouteResult.from(hubRoute);
    }

    // 같은 허브·없는 허브 ID는 에러 없이 빈 목록
    public Page<HubRouteResult> getHubRoutes(HubRouteSearchCondition condition, Pageable pageable) {
        return hubRouteRepository.findAll(
                        search(condition.departureHubId(), condition.arrivalHubId()),
                        toStablePageable(pageable, SORTABLE_FIELDS))
                .map(HubRouteResult::from);
    }

    // 삭제된 경로는 @SQLRestriction으로 조회되지 않아 없는 경로와 같이 404로 응답한다.
    public HubRouteResult getHubRouteById(UUID routeId) {
        HubRoute hubRoute = hubRouteRepository.findById(routeId)
                .orElseThrow(() -> HubExceptions.hubRouteNotFound("routeId"));
        return HubRouteResult.from(hubRoute);
    }

}
