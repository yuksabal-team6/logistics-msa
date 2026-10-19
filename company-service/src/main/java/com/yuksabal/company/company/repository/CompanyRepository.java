package com.yuksabal.company.company.repository;

import com.yuksabal.company.company.entity.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyRepository extends
        JpaRepository<Company, UUID>,
        JpaSpecificationExecutor<Company> {

    boolean existsByBusinessNumber(String businessNumber);

    // 한 회원은 한 업체만 관리한다. 삭제된 업체는 @SQLRestriction으로 빠진다
    boolean existsByOwnerId(UUID ownerId);

    // 관리자 ID로 업체 조회 (내부 API #11). 삭제된 업체는 @SQLRestriction으로 빠진다
    List<Company> findAllByOwnerId(UUID ownerId);

    // 업체 행을 잠그고 조회 (#147). 업체 상태 변경·상품 등록·상품 활성화가 같은 업체 행을 먼저 잠가 순서대로 처리된다
    // → 업체 비활성화와 상품 등록이 겹쳐도 비활성 업체에 활성 상품이 남지 않는다. 삭제된 업체는 @SQLRestriction으로 빠진다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Company c where c.id = :id")
    Optional<Company> findByIdForUpdate(@Param("id") UUID id);
}
