package com.yuksabal.company.global.deletion;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.global.client.OrderClient;
import com.yuksabal.company.global.client.dto.response.OrderInProgressResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;

// order 진행 중 주문 확인 (#137). 진행 중인 주문이 있으면 삭제할 수 없다
// 업체는 공급 업체·요청 업체로 걸린 주문을 모두 본다 (#143 order 담당자 제안: 거래가 진행 중인 업체는 어느 쪽이든 지우지 않는다)
@Component
@RequiredArgsConstructor
public class OrderDeletionChecker implements DeletionChecker {

    private final OrderClient orderClient;

    @Override
    public String type() {
        return "order";
    }

    @Override
    public List<String> findBlockers(DeletionTarget target, UUID id) {
        ApiResponse<OrderInProgressResponse> response = switch (target) {
            case PRODUCT -> orderClient.getInProgressByProduct(id);
            case COMPANY -> orderClient.getInProgressByCompany(id);
        };
        // 응답이나 필드가 비어 있으면(계약 불일치 포함) 확인하지 못한 것으로 보고 막는다
        if (response == null || response.data() == null || response.data().hasInProgressOrder() == null) {
            throw new BusinessException(DEPENDENCY_FAILURE);
        }
        return response.data().hasInProgressOrder() ? List.of("진행 중인 주문이 있습니다.") : List.of();
    }
}
