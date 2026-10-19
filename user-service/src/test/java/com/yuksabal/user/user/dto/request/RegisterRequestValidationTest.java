package com.yuksabal.user.user.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRequestValidationTest {

    private static final String VALID_USERNAME = "user01";
    private static final String VALID_PASSWORD = "Strong123!";
    private static final String VALID_SLACK_ID = "U1";
    private static final String VALID_EMAIL = "a@b.com";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void 정상_요청은_통과() {
        // given
        RegisterRequest request = request(VALID_USERNAME, VALID_PASSWORD, "U12345678", "user01@example.com");

        // when & then
        assertThat(validator.validate(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcd", "user01", "abcdefghij", "1234"})
    void 아이디_규칙에_맞으면_통과(String username) {
        // when & then
        assertThat(violatedFields(requestWithUsername(username))).doesNotContain("username");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "abcdefghijk", "User01", "user_01", "user 01", "유저01"})
    void 아이디_규칙에_어긋나면_실패(String username) {
        // when & then
        assertThat(violatedFields(requestWithUsername(username))).contains("username");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Strong123!", "Aa1!aaaa", "Aa1!aaaaaaaaaaa"})
    void 비밀번호_규칙에_맞으면_통과(String password) {
        // when & then
        assertThat(violatedFields(requestWithPassword(password))).doesNotContain("password");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "Aa1!aaa",            // 7자
            "Aa1!aaaaaaaaaaaa",   // 16자
            "strong123!",         // 대문자 없음
            "STRONG123!",         // 소문자 없음
            "Strongabc!",         // 숫자 없음
            "Strong1234",         // 특수문자 없음
            "Strong 123!"         // 공백
    })
    void 비밀번호_규칙에_어긋나면_실패(String password) {
        // when & then
        assertThat(violatedFields(requestWithPassword(password))).contains("password");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-an-email", "a@"})
    void 이메일이_없거나_형식이_틀리면_실패(String email) {
        // when & then
        assertThat(violatedFields(requestWithEmail(email))).contains("email");
    }

    @Test
    void SlackID가_공백이면_실패() {
        // when & then
        assertThat(violatedFields(requestWithSlackId(" "))).contains("slackId");
    }

    @Test
    void SlackID가_100자를_넘으면_실패() {
        // given
        String slackId101 = "U".repeat(101);

        // when & then
        assertThat(violatedFields(requestWithSlackId(slackId101))).contains("slackId");
    }

    @Test
    void toString에_비밀번호가_없다() {
        // given
        RegisterRequest request = request(VALID_USERNAME, VALID_PASSWORD, VALID_SLACK_ID, VALID_EMAIL);

        // when & then
        assertThat(request.toString()).doesNotContain(VALID_PASSWORD);
    }

    private RegisterRequest request(String username, String password, String slackId, String email) {
        return new RegisterRequest(username, password, slackId, email, null, null);
    }

    private RegisterRequest requestWithUsername(String username) {
        return request(username, VALID_PASSWORD, VALID_SLACK_ID, VALID_EMAIL);
    }

    private RegisterRequest requestWithPassword(String password) {
        return request(VALID_USERNAME, password, VALID_SLACK_ID, VALID_EMAIL);
    }

    private RegisterRequest requestWithSlackId(String slackId) {
        return request(VALID_USERNAME, VALID_PASSWORD, slackId, VALID_EMAIL);
    }

    private RegisterRequest requestWithEmail(String email) {
        return request(VALID_USERNAME, VALID_PASSWORD, VALID_SLACK_ID, email);
    }

    private Set<String> violatedFields(RegisterRequest request) {
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
    }
}
