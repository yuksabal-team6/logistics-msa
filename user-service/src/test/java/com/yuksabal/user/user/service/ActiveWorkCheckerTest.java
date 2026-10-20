package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.user.client.DeliveryClient;
import com.yuksabal.user.user.client.OrderClient;
import com.yuksabal.user.user.client.dto.DeliveryInProgressResponse;
import com.yuksabal.user.user.client.dto.OrderInProgressResponse;
import com.yuksabal.user.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// USE-15 의존성 장애, USE-16 삭제 제한: 진행 중 주문·배송 확인 구현체 (OrderClient·DeliveryClient Mock)
// 응답이 비어 있으면 "없음"이 아니라 "확인하지 못함"으로 보고 502로 막는다
class ActiveWorkCheckerTest {

    private final OrderClient orderClient = mock(OrderClient.class);
    private final DeliveryClient deliveryClient = mock(DeliveryClient.class);
    private final OrderActiveWorkChecker orderChecker = new OrderActiveWorkChecker(orderClient);
    private final DeliveryActiveWorkChecker deliveryChecker = new DeliveryActiveWorkChecker(deliveryClient);

    // 주문·배송은 역할과 관계없이 확인하므로 USER로 둔다
    private final User user = user();

    @Test
    void 진행_중_주문이_있으면_order로_알려준다() {
        // given
        given(orderClient.getInProgressOrder(user.getId()))
                .willReturn(ApiResponse.success(new OrderInProgressResponse(user.getId(), true)));

        // when & then
        assertThat(orderChecker.findActiveWork(user))
                .extracting(ActiveWorkChecker.ActiveWork::type).containsExactly("order");
    }

    @Test
    void 진행_중_주문이_없으면_빈_목록() {
        // given
        given(orderClient.getInProgressOrder(user.getId()))
                .willReturn(ApiResponse.success(new OrderInProgressResponse(user.getId(), false)));

        // when & then
        assertThat(orderChecker.findActiveWork(user)).isEmpty();
    }

    @Test
    void 주문_확인_응답이_비어_있으면_502() {
        // given
        given(orderClient.getInProgressOrder(user.getId())).willReturn(ApiResponse.success(null));

        // when & then
        assertDependencyFailure(() -> orderChecker.findActiveWork(user));
    }

    @Test
    void 진행_중_배송이_있으면_delivery로_알려준다() {
        // given
        given(deliveryClient.getInProgressDelivery(user.getId()))
                .willReturn(ApiResponse.success(new DeliveryInProgressResponse(user.getId(), true)));

        // when & then
        assertThat(deliveryChecker.findActiveWork(user))
                .extracting(ActiveWorkChecker.ActiveWork::type).containsExactly("delivery");
    }

    @Test
    void 진행_중_배송이_없으면_빈_목록() {
        // given
        given(deliveryClient.getInProgressDelivery(user.getId()))
                .willReturn(ApiResponse.success(new DeliveryInProgressResponse(user.getId(), false)));

        // when & then
        assertThat(deliveryChecker.findActiveWork(user)).isEmpty();
    }

    @Test
    void 배송_확인_응답이_없으면_502() {
        // given
        given(deliveryClient.getInProgressDelivery(user.getId())).willReturn(null);

        // when & then
        assertDependencyFailure(() -> deliveryChecker.findActiveWork(user));
    }

    private void assertDependencyFailure(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.DEPENDENCY_FAILURE);
    }

    private static User user() {
        User user = User.builder().username("user01").password("$2a$hash").email("user01@example.com")
                .slackId("U1").role(UserRole.USER).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
