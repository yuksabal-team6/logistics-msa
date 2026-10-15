package com.yuksabal.company.product.repository;

import com.yuksabal.company.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface ProductRepository extends
        JpaRepository<Product, UUID>,
        JpaSpecificationExecutor<Product> {

    // 업체 삭제 시 함께 지울 상품 조회. 이미 삭제된 상품은 @SQLRestriction으로 빠진다
    List<Product> findAllByCompanyId(UUID companyId);
}
