package com.yuksabal.user.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "비밀번호 변경 요청")
public record ChangePasswordRequest(

        @Schema(description = "현재 비밀번호", example = "Strong123!")
        @NotBlank(message = "현재 비밀번호는 필수입니다.")
        String currentPassword,

        @Schema(description = "새 비밀번호 (8~15자, 대문자·소문자·숫자·특수문자 각 1개 이상)", example = "Newpass123!")
        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Pattern(regexp = UserFieldRules.PASSWORD_REGEXP, message = UserFieldRules.PASSWORD_MESSAGE)
        String newPassword
) {

    // 로그 등에 요청이 찍혀도 비밀번호가 노출되지 않게 한다
    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=****, newPassword=****]";
    }
}
