package com.yuksabal.user.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 보낸 필드만 수정한다 (null = 변경 없음). 규칙은 회원가입과 같다.
// 수정 가능: username, email, slackId, isPublic. role·hubId·companyId·password는 받지 않으며 요청에 있어도 무시된다.
@Schema(description = "본인 프로필 수정 요청 (보낸 필드만 수정)")
public record UpdateMeRequest(

        @Schema(description = "로그인 아이디 (소문자·숫자 4~10자)", example = "user02")
        @Pattern(regexp = UserFieldRules.USERNAME_REGEXP, message = UserFieldRules.USERNAME_MESSAGE)
        String username,

        @Schema(description = "이메일", example = "user02@example.com")
        @Pattern(regexp = UserFieldRules.NOT_BLANK_IF_PRESENT_REGEXP, message = "이메일은 비워둘 수 없습니다.")
        @Email(message = "올바른 이메일 형식이어야 합니다.")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
        String email,

        @Schema(description = "Slack ID", example = "U87654321")
        @Pattern(regexp = UserFieldRules.NOT_BLANK_IF_PRESENT_REGEXP, message = "Slack ID는 비워둘 수 없습니다.")
        @Size(max = 100, message = "Slack ID는 100자 이하여야 합니다.")
        String slackId,

        @Schema(description = "정보 공개 여부", example = "false")
        Boolean isPublic
) {

    // getter 이름(isXxx)이면 Swagger·Jackson이 속성으로 인식하므로 피한다
    public boolean hasNoChanges() {
        return username == null && email == null && slackId == null && isPublic == null;
    }
}
