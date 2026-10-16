package com.yuksabal.company.company.repository;

import com.yuksabal.common.util.LikeUtils;
import com.yuksabal.company.company.entity.Company;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

// 업체 목록 검색 조건. 값이 없으면 null을 반환해 조건에서 빠진다
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CompanySpecification {

    public static Specification<Company> hubIdEquals(UUID hubId) {
        return (root, query, cb) ->
                hubId == null
                        ? null
                        : cb.equal(root.get("hubId"), hubId);
    }

    public static Specification<Company> companyNameContains(String companyName) {
        return (root, query, cb) ->
                companyName == null || companyName.isBlank()
                        ? null
                        : cb.like(root.get("companyName"), LikeUtils.contains(companyName), LikeUtils.ESCAPE_CHAR);
    }
}
