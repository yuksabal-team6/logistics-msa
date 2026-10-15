package com.yuksabal.hub.route.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.hub.route.entity.HubRoute;
import com.yuksabal.hub.route.repository.HubRouteRepository;
import com.yuksabal.hub.route.dto.request.HubRouteSearchRequest;
import com.yuksabal.hub.route.dto.response.HubRouteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static com.yuksabal.hub.global.util.PageableUtils.toStablePageable;
import static com.yuksabal.hub.route.repository.HubRouteSpecification.search;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HubRouteService {

    // 목록 정렬 허용 필드 (API 명세 0.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "distanceKm", "durationMinutes");

    private final HubRouteRepository hubRouteRepository;

    // 같은 허브끼리는 경로가 없고(결정 #16), 없는 허브 ID도 경로가 없으므로 모두 404로 응답한다.
    public HubRouteResponse getHubRoute(UUID from, UUID to) {
        HubRoute hubRoute = hubRouteRepository.findByDepartureHub_IdAndArrivalHub_Id(from, to)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않는 허브 경로입니다."));
        return HubRouteResponse.from(hubRoute);
    }

    // 같은 허브·없는 허브 ID는 에러 없이 빈 목록
    public PageResponse<HubRouteResponse> getHubRoutes(HubRouteSearchRequest request, Pageable pageable) {
        Page<HubRoute> hubRoutes = hubRouteRepository.findAll(
                search(request.departureHubId(), request.arrivalHubId()),
                toStablePageable(pageable, SORTABLE_FIELDS));
        return PageResponse.from(hubRoutes, HubRouteResponse::from);
    }
}
