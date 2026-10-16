package com.yuksabal.company.company.repository;

import com.yuksabal.company.company.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface CompanyRepository extends
        JpaRepository<Company, UUID>,
        JpaSpecificationExecutor<Company> {

    boolean existsByBusinessNumber(String businessNumber);

    // 한 회원은 한 업체만 관리한다. 삭제된 업체는 @SQLRestriction으로 빠진다
    boolean existsByOwnerId(UUID ownerId);

    // 관리자 ID로 업체 조회 (내부 API #11). 삭제된 업체는 @SQLRestriction으로 빠진다
    List<Company> findAllByOwnerId(UUID ownerId);
}
