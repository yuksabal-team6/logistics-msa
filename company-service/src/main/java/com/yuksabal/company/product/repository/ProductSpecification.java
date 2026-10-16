package com.yuksabal.company.product.repository;

import com.yuksabal.common.util.LikeUtils;
import com.yuksabal.company.product.entity.Product;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

// 상품 목록 검색 조건. 값이 없으면 null을 반환해 조건에서 빠진다
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductSpecification {

    public static Specification<Product> productNameContains(String productName) {
        return (root, query, cb) ->
                productName == null || productName.isBlank()
                        ? null
                        : cb.like(root.get("productName"), LikeUtils.contains(productName), LikeUtils.ESCAPE_CHAR);
    }

    // company.id는 상품 테이블의 FK(company_id)라 업체 조인 없이 비교된다
    public static Specification<Product> companyIdEquals(UUID companyId) {
        return (root, query, cb) ->
                companyId == null
                        ? null
                        : cb.equal(root.get("company").get("id"), companyId);
    }

    // 관리 허브는 업체 테이블에만 있어 업체와 조인해서 비교한다
    public static Specification<Product> hubIdEquals(UUID hubId) {
        return (root, query, cb) ->
                hubId == null
                        ? null
                        : cb.equal(root.get("company").get("hubId"), hubId);
    }
}
