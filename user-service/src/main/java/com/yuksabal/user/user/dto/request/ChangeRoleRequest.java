package com.yuksabal.user.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// 문자열로 받아 형식을 검사한다 (enum으로 바로 받으면 변환 실패 메시지가 내부 타입 정보를 노출한다)
@Schema(description = "역할 변경 요청 (MASTER)")
public record ChangeRoleRequest(

        @Schema(description = "변경할 역할 (MASTER, USER, DELIVERY, COMPANY)", example = "COMPANY")
        @NotBlank(message = "역할은 필수입니다.")
        @Pattern(regexp = "^(MASTER|USER|DELIVERY|COMPANY)$", message = "역할은 MASTER, USER, DELIVERY, COMPANY 중 하나여야 합니다.")
        String role
) {
}
