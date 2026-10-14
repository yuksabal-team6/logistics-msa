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
        assertThat(validator.validate(request("user01", "Strong123!", "U12345678", "user01@example.com"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcd", "user01", "abcdefghij", "1234"})
    void 아이디_규칙에_맞으면_통과(String username) {
        assertThat(violatedFields(request(username, "Strong123!", "U1", "a@b.com"))).doesNotContain("username");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "abcdefghijk", "User01", "user_01", "user 01", "유저01"})
    void 아이디_규칙에_어긋나면_실패(String username) {
        assertThat(violatedFields(request(username, "Strong123!", "U1", "a@b.com"))).contains("username");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Strong123!", "Aa1!aaaa", "Aa1!aaaaaaaaaaa"})
    void 비밀번호_규칙에_맞으면_통과(String password) {
        assertThat(violatedFields(request("user01", password, "U1", "a@b.com"))).doesNotContain("password");
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
        assertThat(violatedFields(request("user01", password, "U1", "a@b.com"))).contains("password");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-an-email", "a@"})
    void 이메일이_없거나_형식이_틀리면_실패(String email) {
        assertThat(violatedFields(request("user01", "Strong123!", "U1", email))).contains("email");
    }

    @Test
    void SlackID가_없거나_100자를_넘으면_실패() {
        assertThat(violatedFields(request("user01", "Strong123!", " ", "a@b.com"))).contains("slackId");
        assertThat(violatedFields(request("user01", "Strong123!", "U".repeat(101), "a@b.com"))).contains("slackId");
    }

    @Test
    void toString에_비밀번호가_없다() {
        assertThat(request("user01", "Strong123!", "U1", "a@b.com").toString()).doesNotContain("Strong123!");
    }

    private RegisterRequest request(String username, String password, String slackId, String email) {
        return new RegisterRequest(username, password, slackId, email, null, null);
    }

    private Set<String> violatedFields(RegisterRequest request) {
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }
}
