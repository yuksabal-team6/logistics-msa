package com.yuksabal.order.order.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.order.global.exception.OrderErrorCode;
import com.yuksabal.order.order.dto.request.OrderCreateRequest;
import com.yuksabal.order.order.dto.request.OrderSearchRequest;
import com.yuksabal.order.order.dto.response.OrderCreateResponse;
import com.yuksabal.order.order.dto.response.OrderResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

// API 스펙만 먼저 만든 상태 (이슈 #47). 메서드마다 501 예외, 후속 이슈에서 구현
// 생성: Saga·멱등성 (결정 #17, #18) / 조회·삭제: 권한·삭제 조건 / 취소: #9·#4 (결정 #19)
@Service
public class OrderService {

    public OrderCreateResponse createOrder(OrderCreateRequest request, UUID idempotencyKey, UUID userId, String role) {
        throw new BusinessException(OrderErrorCode.NOT_IMPLEMENTED);
    }

    public PageResponse<OrderResponse> getOrders(OrderSearchRequest request, Pageable pageable, UUID userId, String role) {
        throw new BusinessException(OrderErrorCode.NOT_IMPLEMENTED);
    }

    public OrderResponse getOrder(UUID orderId, UUID userId, String role) {
        throw new BusinessException(OrderErrorCode.NOT_IMPLEMENTED);
    }

    public OrderResponse cancelOrder(UUID orderId, UUID userId, String role) {
        throw new BusinessException(OrderErrorCode.NOT_IMPLEMENTED);
    }

    public void deleteOrder(UUID orderId, UUID userId, String role) {
        throw new BusinessException(OrderErrorCode.NOT_IMPLEMENTED);
    }
}
