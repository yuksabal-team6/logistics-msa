package com.yuksabal.hub.hub.application;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.hub.hub.application.dto.HubResult;
import com.yuksabal.hub.hub.application.dto.HubSearchCondition;
import com.yuksabal.hub.hub.domain.Hub;
import com.yuksabal.hub.hub.domain.HubRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// Repository는 가짜로 두고, 응답 변환과 예외 코드만 확인한다 (keyword 검색 조건은 HubSpecificationTest)
@ExtendWith(MockitoExtension.class)
class HubServiceTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private HubRepository hubRepository;

    @InjectMocks
    private HubService hubService;

    @Nested
    class 허브_목록_조회 {

        // 초기 허브는 created_at이 모두 같아서, 정렬 값이 같을 때 페이지 간 중복·누락이 없도록 id를 붙인다
        @Test
        void 정렬_기준_끝에_id를_붙여_조회한다() {
            given(hubRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .willAnswer(invocation -> new PageImpl<>(List.of(seoul()), invocation.getArgument(1), 1));

            hubService.getHubs(new HubSearchCondition("서울"), PageRequest.of(0, 10, Sort.by("createdAt")));

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            then(hubRepository).should().findAll(any(Specification.class), pageable.capture());
            assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
        }
    }

    @Nested
    class 허브_상세_조회 {

        @Test
        void 허브가_있으면_결과로_바꾼다() {
            given(hubRepository.findById(SEOUL_ID)).willReturn(Optional.of(seoul()));

            HubResult result = hubService.getHub(SEOUL_ID);

            assertThat(result.hubId()).isEqualTo(SEOUL_ID);
            assertThat(result.hubName()).isEqualTo("서울특별시 센터");
        }

        // 삭제된 허브는 @SQLRestriction으로 조회되지 않아 없는 허브와 같다
        // 공개 상세와 내부 API(company·user-service의 허브 존재 확인)가 같은 404를 받는다 (#284)
        @Test
        void 허브가_없으면_404() {
            given(hubRepository.findById(SEOUL_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> hubService.getHub(SEOUL_ID))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getMessage()).isEqualTo("존재하지 않는 허브입니다.");
                        assertThat(e.getDetails()).extracting("field").containsExactly("hubId");
                    });
        }
    }

    private static Hub seoul() {
        Hub hub = Hub.builder()
                .hubName("서울특별시 센터")
                .address("서울특별시 송파구 송파대로 55")
                .latitude(new BigDecimal("37.4747"))
                .longitude(new BigDecimal("127.1236"))
                .build();
        ReflectionTestUtils.setField(hub, "id", SEOUL_ID);
        return hub;
    }
}
