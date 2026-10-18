package com.yuksabal.order.order.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.order.global.exception.OrderErrorCode;
import com.yuksabal.order.order.client.CompanyClient;
import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.HubClient;
import com.yuksabal.order.order.client.UserClient;
import com.yuksabal.order.order.dto.request.OrderSearchRequest;
import com.yuksabal.order.order.dto.response.OrderResponse;
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.notification.SlackNotifier;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    // 조회/삭제 테스트라 외부 호출과 트랜잭션은 쓰지 않음
    private final OrderService orderService = new OrderService(orderRepository,
            mock(UserClient.class), mock(CompanyClient.class), mock(HubClient.class), mock(DeliveryClient.class),
            mock(SlackNotifier.class), mock(TransactionTemplate.class));

    private final UUID ordererId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @Test
    void 상세_조회_주문자_본인은_성공() {
        given(orderRepository.findById(orderId)).willReturn(Optional.of(order(OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED)));

        OrderResponse response = orderService.getOrder(orderId, ordererId, UserRole.USER);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.orderNumber()).isEqualTo("TEST-1");
    }

    @Test
    void 상세_조회_MASTER는_남의_주문도_성공() {
        given(orderRepository.findById(orderId)).willReturn(Optional.of(order(OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED)));

        assertThat(orderService.getOrder(orderId, masterId, UserRole.MASTER).orderId()).isEqualTo(orderId);
    }

    @Test
    void 상세_조회_남의_주문은_403() {
        given(orderRepository.findById(orderId)).willReturn(Optional.of(order(OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED)));

        assertThatThrownBy(() -> orderService.getOrder(orderId, otherUserId, UserRole.COMPANY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 상세_조회_없거나_삭제된_주문은_404() {
        given(orderRepository.findById(orderId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(orderId, masterId, UserRole.MASTER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 삭제_MASTER가_아니면_403_조회도_안함() {
        assertThatThrownBy(() -> orderService.deleteOrder(orderId, ordererId, UserRole.USER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.FORBIDDEN);
        verify(orderRepository, never()).findById(any());
    }

    @Test
    void 삭제_진행_중인_주문은_409() {
        Order order = order(OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED);
        given(orderRepository.findById(orderId)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.deleteOrder(orderId, masterId, UserRole.MASTER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.ORDER_NOT_DELETABLE);
        assertThat(order.getDeletedAt()).isNull();
    }

    @Test
    void 삭제_완료_주문은_삭제자와_시각_기록() {
        Order order = order(OrderStatus.COMPLETED, CompensationStatus.NOT_REQUIRED);
        given(orderRepository.findById(orderId)).willReturn(Optional.of(order));

        orderService.deleteOrder(orderId, masterId, UserRole.MASTER);

        assertThat(order.getDeletedAt()).isNotNull();
        assertThat(order.getDeletedBy()).isEqualTo(masterId);
    }

    @Test
    void 목록_허용하지_않은_정렬_필드는_400() {
        OrderSearchRequest request = new OrderSearchRequest(null, null, null, null, null, null);

        assertThatThrownBy(() -> orderService.getOrders(request, PageRequest.of(0, 20, Sort.by("quantity")), masterId, UserRole.MASTER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.VALIDATION_ERROR);
    }

    private Order order(OrderStatus status, CompensationStatus compensationStatus) {
        Order order = Order.builder()
                .requestCompanyId(UUID.randomUUID())
                .supplierCompanyId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID())
                .arrivalHubId(UUID.randomUUID())
                .ordererId(ordererId)
                .orderNumber("TEST-1")
                .quantity(5)
                .unitPrice(12_000L)
                .build();
        ReflectionTestUtils.setField(order, "id", orderId);
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "compensationStatus", compensationStatus);
        return order;
    }
}
