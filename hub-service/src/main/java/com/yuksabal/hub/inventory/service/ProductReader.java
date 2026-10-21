package com.yuksabal.hub.inventory.service;

import java.util.Optional;
import java.util.UUID;

// 재고 서비스가 필요한 상품 정보 조회. 어디서 가져오는지(company-service Feign)는 구현(CompanyFeignAdapter)이 맡는다
// 없거나 삭제된 상품이면 empty. 그 상황을 404로 할지는 쓰는 쪽이 정한다
public interface ProductReader {

    Optional<ProductInfo> findProduct(UUID productId);
}
