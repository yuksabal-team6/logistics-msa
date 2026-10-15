package com.yuksabal.user.user.dto.request;

// 회원가입·프로필 수정·비밀번호 변경이 같은 규칙을 쓰도록 한곳에 모은다 (어노테이션 값이라 상수여야 한다)
public final class UserFieldRules {

    public static final String USERNAME_REGEXP = "^[a-z0-9]{4,10}$";
    public static final String USERNAME_MESSAGE = "아이디는 4~10자의 알파벳 소문자와 숫자로 입력해주세요.";

    public static final String PASSWORD_REGEXP = "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^a-zA-Z0-9\\s])\\S{8,15}$";
    public static final String PASSWORD_MESSAGE = "비밀번호는 8~15자이며 대문자, 소문자, 숫자, 특수문자를 모두 포함해야 합니다.";

    // 선택 필드용: 값이 오면 공백만으로는 채울 수 없다 (null은 통과)
    public static final String NOT_BLANK_IF_PRESENT_REGEXP = "^(?!\\s*$).+";

    private UserFieldRules() {
    }
}
