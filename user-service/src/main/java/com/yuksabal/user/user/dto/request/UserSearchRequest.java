package com.yuksabal.user.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 회원 목록 검색 조건 (모두 선택). role·hubId는 문자열로 받아 형식을 검사한다
// (enum·UUID로 바로 받으면 변환 실패 메시지가 내부 타입 정보를 그대로 노출한다)
@Schema(description = "회원 검색 요청")
public record UserSearchRequest(

        @Schema(description = "로그인 아이디 (일부만 입력해도 검색). 생략 시 조건 없음", example = "user")
        @Size(max = 100, message = "아이디는 100자 이하로 입력해주세요.")
        String username,

        @Schema(description = "역할 (MASTER, USER, DELIVERY, COMPANY). 생략 시 조건 없음", example = "COMPANY")
        @Pattern(regexp = "^(MASTER|USER|DELIVERY|COMPANY)$", message = "역할은 MASTER, USER, DELIVERY, COMPANY 중 하나여야 합니다.")
        String role,

        @Schema(description = "소속 허브 ID (정확히 일치). 생략 시 조건 없음", example = "00000000-0000-0000-0000-000000000004")
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "허브 ID는 UUID 형식이어야 합니다.")
        String hubId
) {
}
