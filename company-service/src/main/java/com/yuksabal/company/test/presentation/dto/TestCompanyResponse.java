package com.yuksabal.company.test.presentation.dto;

import com.yuksabal.company.company.domain.Company;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// [세팅 검증용] Audit 필드까지 그대로 보여준다
@Schema(description = "테스트 업체 응답 (Audit 필드 포함)")
public record TestCompanyResponse(
        UUID companyId,
        String companyName,
        UUID hubId,
        String hubName,
        UUID ownerId,
        String address,
        LocalDateTime createdAt,
        UUID createdBy,
        LocalDateTime updatedAt,
        UUID updatedBy,
        LocalDateTime deletedAt,
        UUID deletedBy
) {

    public static TestCompanyResponse of(Company company, String hubName) {
        return new TestCompanyResponse(
                company.getId(),
                company.getCompanyName(),
                company.getHubId(),
                hubName,
                company.getOwnerId(),
                company.getAddress(),
                company.getCreatedAt(),
                company.getCreatedBy(),
                company.getUpdatedAt(),
                company.getUpdatedBy(),
                company.getDeletedAt(),
                company.getDeletedBy()
        );
    }
}
