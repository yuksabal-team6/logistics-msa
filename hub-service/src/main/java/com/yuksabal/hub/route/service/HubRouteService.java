package com.yuksabal.hub.route.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.hub.route.domain.HubRoute;
import com.yuksabal.hub.route.domain.HubRouteRepository;
import com.yuksabal.hub.route.dto.response.HubRouteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HubRouteService {

    private final HubRouteRepository hubRouteRepository;

    // 같은 허브끼리는 경로가 없고(결정 #16), 없는 허브 ID도 경로가 없으므로 모두 404로 응답한다.
    public HubRouteResponse getHubRoute(UUID from, UUID to) {
        HubRoute hubRoute = hubRouteRepository.findByDepartureHub_IdAndArrivalHub_Id(from, to)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않는 허브 경로입니다."));
        return HubRouteResponse.from(hubRoute);
    }
}
