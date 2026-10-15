package com.yuksabal.user.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// 형식 검증(아이디·비밀번호 규칙)은 하지 않는다. 형식이 틀린 값은 어차피 일치하는 회원이 없어 401로 응답된다.
@Schema(description = "로그인 요청")
public record LoginRequest(

        @Schema(description = "로그인 아이디", example = "user01")
        @NotBlank(message = "아이디는 필수입니다.")
        String username,

        @Schema(description = "비밀번호", example = "Strong123!")
        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {

    // 로그 등에 요청이 찍혀도 비밀번호가 노출되지 않게 한다
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=****]";
    }
}
