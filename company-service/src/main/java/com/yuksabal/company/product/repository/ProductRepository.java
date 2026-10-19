package com.yuksabal.company.product.repository;

import com.yuksabal.company.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends
        JpaRepository<Product, UUID>,
        JpaSpecificationExecutor<Product> {

    // 업체 삭제 시 함께 지울 상품 조회. 이미 삭제된 상품은 @SQLRestriction으로 빠진다
    List<Product> findAllByCompanyId(UUID companyId);

    // 상품의 업체 ID만 조회한다 (상품 엔티티를 영속성 컨텍스트에 올리지 않음, #147)
    // 업체 행을 잠근 뒤에 상품을 읽어야 잠금을 기다리는 동안 바뀐 상품 상태를 놓치지 않는다
    @Query("select p.company.id from Product p where p.id = :productId")
    Optional<UUID> findCompanyIdById(@Param("productId") UUID productId);
}
