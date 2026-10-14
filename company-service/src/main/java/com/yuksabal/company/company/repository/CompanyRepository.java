package com.yuksabal.company.company.repository;

import com.yuksabal.company.company.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface CompanyRepository extends
        JpaRepository<Company, UUID>,
        JpaSpecificationExecutor<Company> {

    boolean existsByBusinessNumber(String businessNumber);
}
