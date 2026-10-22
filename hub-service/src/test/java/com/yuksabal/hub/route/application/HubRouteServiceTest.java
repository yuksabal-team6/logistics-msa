package com.yuksabal.hub.route.application;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.hub.hub.domain.Hub;
import com.yuksabal.hub.route.presentation.dto.request.HubRouteSearchRequest;
import com.yuksabal.hub.route.presentation.dto.response.HubRouteResponse;
import com.yuksabal.hub.route.domain.HubRoute;
import com.yuksabal.hub.route.domain.HubRouteRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

// Repository는 가짜로 두고, 응답 변환과 예외 코드만 확인한다 (출발·도착 검색 조건은 HubRouteControllerTest)
@ExtendWith(MockitoExtension.class)
class HubRouteServiceTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BUSAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ROUTE_ID = UUID.fromString("00000000-0000-0000-0001-000000000004");

    @Mock
    private HubRouteRepository hubRouteRepository;

    @InjectMocks
    private HubRouteService hubRouteService;

    // delivery-service가 배송 경로를 만들 때 호출한다
    @Nested
    class 허브_간_경로_조회 {

        @Test
        void 경로가_있으면_출발_도착_거리_시간을_응답한다() {
            given(hubRouteRepository.findByDepartureHub_IdAndArrivalHub_Id(SEOUL_ID, BUSAN_ID)).willReturn(Optional.of(seoulToBusan()));

            HubRouteResponse response = hubRouteService.getHubRoute(SEOUL_ID, BUSAN_ID);

            assertThat(response.departureHubId()).isEqualTo(SEOUL_ID);
            assertThat(response.arrivalHubId()).isEqualTo(BUSAN_ID);
            assertThat(response.distanceKm()).isEqualByComparingTo("421.50");
            assertThat(response.durationMinutes()).isEqualTo(422);
        }

        // 같은 허브끼리는 경로가 없고(결정 #16), 없는 허브 ID도 경로가 없어 모두 404
        @Test
        void 경로가_없으면_404() {
            given(hubRouteRepository.findByDepartureHub_IdAndArrivalHub_Id(SEOUL_ID, SEOUL_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> hubRouteService.getHubRoute(SEOUL_ID, SEOUL_ID))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND));
        }
    }

    @Nested
    class 경로_목록_조회 {

        @Test
        void 허용하지_않은_정렬_필드면_400이고_경로를_조회하지_않는다() {
            PageRequest pageable = PageRequest.of(0, 10, Sort.by("departureHub"));

            assertThatThrownBy(() -> hubRouteService.getHubRoutes(new HubRouteSearchRequest(null, null), pageable))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(VALIDATION_ERROR);
                        assertThat(e.getDetails()).extracting("field").containsExactly("sort");
                    });

            verifyNoInteractions(hubRouteRepository);
        }
    }

    @Nested
    class 경로_상세_조회 {

        @Test
        void 경로가_있으면_응답으로_바꾼다() {
            given(hubRouteRepository.findById(ROUTE_ID)).willReturn(Optional.of(seoulToBusan()));

            assertThat(hubRouteService.getHubRouteById(ROUTE_ID).hubRouteId()).isEqualTo(ROUTE_ID);
        }

        @Test
        void 경로가_없으면_404() {
            given(hubRouteRepository.findById(ROUTE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> hubRouteService.getHubRouteById(ROUTE_ID))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("routeId");
                    });
        }
    }

    private static HubRoute seoulToBusan() {
        HubRoute route = HubRoute.builder()
                .departureHub(hub(SEOUL_ID))
                .arrivalHub(hub(BUSAN_ID))
                .distanceKm(new BigDecimal("421.50"))
                .durationMinutes(422)
                .build();
        ReflectionTestUtils.setField(route, "id", ROUTE_ID);
        return route;
    }

    private static Hub hub(UUID hubId) {
        Hub hub = Hub.builder().hubName("허브").address("주소").build();
        ReflectionTestUtils.setField(hub, "id", hubId);
        return hub;
    }
}
