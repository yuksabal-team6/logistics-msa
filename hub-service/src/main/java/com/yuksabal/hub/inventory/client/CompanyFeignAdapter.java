package com.yuksabal.hub.inventory.client;

import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.hub.inventory.client.dto.CompanyResponse;
import com.yuksabal.hub.inventory.service.CompanyReader;
import com.yuksabal.hub.inventory.service.ProductInfo;
import com.yuksabal.hub.inventory.service.ProductReader;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

// company-service 호출을 재고 서비스가 정의한 ProductReader·CompanyReader로 바꿔 준다 (DIP)
// ApiResponse 언래핑과 Feign 404 변환은 여기에만 둔다. 서비스·정책은 Feign을 모른다
@Component
@RequiredArgsConstructor
public class CompanyFeignAdapter implements ProductReader, CompanyReader {

    private final CompanyClient companyClient;

    // #1 상품 조회
    @Override
    public Optional<ProductInfo> findProduct(UUID productId) {
        return call(() -> companyClient.getProduct(productId).data())
                .map(product -> new ProductInfo(product.productId(), product.companyId(), product.hubId(), product.active()));
    }

    // #2 업체 조회
    @Override
    public Optional<UUID> findOwnerId(UUID companyId) {
        return call(() -> companyClient.getCompany(companyId).data())
                .map(CompanyResponse::ownerId);
    }

    // company가 404(없거나 삭제됨)면 empty. 그 외 4xx는 그대로 던진다 (FeignExceptionHandler가 그 상태로 전달)
    // 5xx·타임아웃(FeignServerException, RetryableException)은 FeignClientException이 아니라 잡지 않는다 (FeignExceptionHandler가 502)
    private <T> Optional<T> call(Supplier<T> request) {
        try {
            return Optional.of(request.get());
        } catch (FeignClientException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
