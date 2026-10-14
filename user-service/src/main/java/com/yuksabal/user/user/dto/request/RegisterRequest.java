package com.yuksabal.user.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// 역할은 받지 않는다 (서버에서 USER로 고정). 요청에 role이 있어도 무시된다.
@Schema(description = "회원가입 요청")
public record RegisterRequest(

        @Schema(description = "로그인 아이디 (소문자·숫자 4~10자)", example = "user01")
        @NotBlank(message = "아이디는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9]{4,10}$", message = "아이디는 4~10자의 알파벳 소문자와 숫자로 입력해주세요.")
        String username,

        @Schema(description = "비밀번호 (8~15자, 대문자·소문자·숫자·특수문자 각 1개 이상)", example = "Strong123!")
        @NotBlank(message = "비밀번호는 필수입니다.")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^a-zA-Z0-9\\s])\\S{8,15}$",
                message = "비밀번호는 8~15자이며 대문자, 소문자, 숫자, 특수문자를 모두 포함해야 합니다.")
        String password,

        @Schema(description = "Slack ID", example = "U12345678")
        @NotBlank(message = "Slack ID는 필수입니다.")
        @Size(max = 100, message = "Slack ID는 100자 이하여야 합니다.")
        String slackId,

        @Schema(description = "이메일", example = "user01@example.com")
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이어야 합니다.")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
        String email,

        @Schema(description = "소속 허브 ID (선택)", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId,

        @Schema(description = "소속 업체 ID (선택)", example = "20000000-0000-4000-8000-000000000001")
        UUID companyId
) {

    // 로그 등에 요청이 찍혀도 비밀번호가 노출되지 않게 한다
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", password=****, slackId=" + slackId + ", email=" + email
                + ", hubId=" + hubId + ", companyId=" + companyId + "]";
    }
}
