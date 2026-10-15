package com.yuksabal.user.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// 계정 활성화(true)·비활성화(false). 소프트 삭제(탈퇴)와 다르며 다시 활성화할 수 있다
@Schema(description = "계정 상태 변경 요청 (MASTER)")
public record ChangeStatusRequest(

        @Schema(description = "활성 여부 (true = 활성화, false = 비활성화)", example = "false")
        @NotNull(message = "활성 여부는 필수입니다.")
        Boolean active
) {
}
