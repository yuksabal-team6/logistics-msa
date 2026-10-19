package com.yuksabal.company.global.deletion;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.global.client.HubClient;
import com.yuksabal.company.global.client.dto.response.InventoryStockResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;

// hub 재고 확인 (#137). 허브에 남은 수량이 있으면 삭제할 수 없다 (수량 0인 재고는 이력이라 막지 않는다)
@Component
@RequiredArgsConstructor
public class InventoryDeletionChecker implements DeletionChecker {

    private final HubClient hubClient;

    @Override
    public String type() {
        return "inventory";
    }

    @Override
    public List<String> findBlockers(DeletionTarget target, UUID id) {
        ApiResponse<InventoryStockResponse> response = switch (target) {
            case PRODUCT -> hubClient.getStockByProduct(id);
            case COMPANY -> hubClient.getStockByCompany(id);
        };
        // 응답이나 필드가 비어 있으면(계약 불일치 포함) 확인하지 못한 것으로 보고 막는다
        if (response == null || response.data() == null || response.data().hasStock() == null) {
            throw new BusinessException(DEPENDENCY_FAILURE);
        }
        return response.data().hasStock() ? List.of("허브에 재고가 남아 있습니다.") : List.of();
    }
}
