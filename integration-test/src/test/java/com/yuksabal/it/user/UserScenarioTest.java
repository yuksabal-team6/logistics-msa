package com.yuksabal.it.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.it.support.Accounts;
import com.yuksabal.it.support.Gateway;
import com.yuksabal.it.support.Gateway.Response;
import com.yuksabal.it.support.Scenario;
import com.yuksabal.it.support.Scenario.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// USE: 회원 시나리오 (노션 테스트 시나리오 창구)
// 테스트 본문은 노션 본문과 같은 순서로 쓴다: 사전 조건 → 이렇게 하면 → 이렇게 돼야 한다
// 시나리오마다 공통 준비(업체 A·B, 상품 P, 재고 10개, 회원 u·a·b)를 새로 만든다 (Scenario 참고)
//
// 회원 상태 변경은 PATCH /api/v1/users/{id}/status {"active": true|false}, MASTER만 할 수 있다
class UserScenarioTest {

    private static final String REGISTER = "/api/v1/auth/register";

    private Scenario s;

    @BeforeEach
    void setUp() {
        s = new Scenario();
    }

    @Test
    @DisplayName("USE-1. 회원가입 및 로그인: 가입 201(역할은 USER 고정), 로그인 200, 중복 409, 형식 오류·없는 허브 400, 잘못된 아이디·비밀번호 401")
    void use1_회원가입_및_로그인() {
        // 사전 조건: 아직 없는 아이디 (Scenario와 같은 규칙: 이름 1자 + 6자리)
        String username = "n" + s.suffix;

        // 이렇게 하면 (1): role을 MASTER로 넣어 가입한 뒤 로그인
        Map<String, Object> body = new HashMap<>(Scenario.registerBody(username));
        body.put("role", "MASTER");
        Response register = Gateway.post(REGISTER, null, body);
        Response login = Accounts.tryLogin(username, Scenario.PASSWORD);

        // 이렇게 돼야 한다 (1): 가입 201, role은 무시되고 USER / 로그인 200, Bearer 토큰
        assertThat(register.status()).as("%s", register).isEqualTo(201);
        assertThat(register.data().path("username").asText()).isEqualTo(username);
        assertThat(register.data().path("role").asText()).isEqualTo("USER");
        assertThat(login.status()).as("%s", login).isEqualTo(200);
        assertThat(login.data().path("accessToken").asText()).isNotBlank();
        assertThat(login.data().path("tokenType").asText()).isEqualTo("Bearer");

        // 이렇게 하면 (2): 같은 아이디·이메일로 다시 가입
        Response duplicate = Gateway.post(REGISTER, null, Scenario.registerBody(username));

        // 이렇게 돼야 한다 (2): 409, 겹친 필드 둘 다 알려준다
        assertThat(duplicate.status()).as("%s", duplicate).isEqualTo(409);
        assertThat(duplicate.errorCode()).isEqualTo("DUPLICATE_RESOURCE");
        assertThat(duplicate.errorFields()).containsExactlyInAnyOrder("username", "email");

        // 이렇게 하면 (3): 형식이 틀린 값, 없는 허브로 가입 (형식 검사가 먼저라 허브 확인 전에 400)
        String other = "m" + s.suffix;
        Map<String, Object> invalid = new HashMap<>(Scenario.registerBody(other));
        invalid.put("username", "AB");
        invalid.put("password", "weak");
        invalid.put("email", "not-an-email");
        Response badFormat = Gateway.post(REGISTER, null, invalid);

        Map<String, Object> unknownHub = new HashMap<>(Scenario.registerBody(other));
        unknownHub.put("hubId", UUID.randomUUID());
        Response badHub = Gateway.post(REGISTER, null, unknownHub);

        // 이렇게 돼야 한다 (3): 둘 다 400, 허브는 hub-service 내부 API가 404라 hubId로 알려준다
        assertThat(badFormat.status()).as("%s", badFormat).isEqualTo(400);
        assertThat(badFormat.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(badFormat.errorFields()).contains("username", "password", "email");
        assertThat(badHub.status()).as("%s", badHub).isEqualTo(400);
        assertThat(badHub.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(badHub.errorFields()).containsExactly("hubId");

        // 이렇게 하면 (4): 비밀번호를 틀리거나, 없는 아이디로 로그인
        Response wrongPassword = Accounts.tryLogin(username, "Wrong123!");
        Response unknownUser = Accounts.tryLogin(other, Scenario.PASSWORD);

        // 이렇게 돼야 한다 (4): 둘 다 같은 401 (가입된 아이디인지 드러내지 않는다)
        // other는 (3)에서 가입에 실패한 아이디라, 401이면 실패한 가입이 회원을 만들지 않았다는 뜻이다
        assertThat(wrongPassword.status()).as("%s", wrongPassword).isEqualTo(401);
        assertThat(wrongPassword.errorCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownUser.status()).as("%s", unknownUser).isEqualTo(401);
        assertThat(unknownUser.errorCode()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("USE-6. 상태 변경 멱등성: 같은 상태로 두 번 바꿔도 200, 비활성이면 로그인·기존 토큰 403, 재활성화 후 로그인 200")
    void use6_상태_변경_멱등성_및_재활성화() {
        // 사전 조건: 비활성화 전에 u의 토큰을 받아 둔다
        String tokenBeforeDeactivate = s.u.token();

        // 이렇게 하면 (1): u를 두 번 비활성화
        Response deactivate = s.changeUserStatus(s.u.userId(), s.master, false);
        Response deactivateAgain = s.changeUserStatus(s.u.userId(), s.master, false);

        // 이렇게 돼야 한다 (1): 두 번 다 200, 비활성 그대로
        assertStatusChanged(deactivate, false);
        assertStatusChanged(deactivateAgain, false);

        // 이렇게 돼야 한다 (2): 로그인 403, 미리 받아 둔 토큰으로 user-service를 불러도 403
        Response login = s.u.tryLogin();
        Response withOldToken = Gateway.get(userPath(s.u.userId()), tokenBeforeDeactivate);

        assertThat(login.status()).as("%s", login).isEqualTo(403);
        assertThat(login.errorCode()).isEqualTo("ACCOUNT_DISABLED");
        assertThat(withOldToken.status()).as("%s", withOldToken).isEqualTo(403);
        assertThat(withOldToken.errorCode()).isEqualTo("ACCOUNT_DISABLED");

        // 이렇게 하면 (3): u를 두 번 재활성화
        Response activate = s.changeUserStatus(s.u.userId(), s.master, true);
        Response activateAgain = s.changeUserStatus(s.u.userId(), s.master, true);

        // 이렇게 돼야 한다 (3): 두 번 다 200, 다시 로그인된다
        assertStatusChanged(activate, true);
        assertStatusChanged(activateAgain, true);
        assertThat(s.u.tryLogin().status()).isEqualTo(200);
    }

    @Test
    @DisplayName("USE-8. 상태 변경 권한: 토큰 없음 401, USER·COMPANY 403, 비활성 MASTER 403, 대상 상태는 그대로")
    void use8_상태_변경_요청_권한() {
        // 사전 조건: 대상은 b
        UUID target = s.b.userId();

        // 이렇게 하면 (1): 토큰 없이, USER(u)·COMPANY(a) 토큰으로 b를 비활성화
        Response noToken = s.changeUserStatus(target, null, false);
        Response byUser = s.changeUserStatus(target, s.u.token(), false);
        Response byCompany = s.changeUserStatus(target, s.a.token(), false);

        // 이렇게 돼야 한다 (1): 토큰 없음 401, MASTER가 아닌 역할 403
        assertThat(noToken.status()).as("%s", noToken).isEqualTo(401);
        assertThat(byUser.status()).as("%s", byUser).isEqualTo(403);
        assertThat(byCompany.status()).as("%s", byCompany).isEqualTo(403);

        // 이렇게 하면 (2): 비활성 MASTER 토큰으로 b를 비활성화 (u를 MASTER로 올려 토큰을 받은 뒤 비활성화해서 만든다)
        s.changeRole(s.u, "MASTER");
        try {
            String disabledMasterToken = s.u.token();
            s.deactivateUser(s.u);

            Response byDisabledMaster = s.changeUserStatus(target, disabledMasterToken, false);

            // 이렇게 돼야 한다 (2): 403 ACCOUNT_DISABLED
            assertThat(byDisabledMaster.status()).as("%s", byDisabledMaster).isEqualTo(403);
            assertThat(byDisabledMaster.errorCode()).isEqualTo("ACCOUNT_DISABLED");
        } finally {
            restoreToUser(s.u);
        }

        // 이렇게 돼야 한다 (3): 거절된 요청은 b의 상태를 바꾸지 않는다
        assertThat(isActive(target)).isTrue();
    }

    // 마지막 MASTER(422)는 API로 재현할 수 없다: 요청자가 활성 MASTER여야 하므로 대상이 마지막 MASTER면 곧 자기 자신이고,
    // 자기 자신 검사(422)에 먼저 걸린다. itmaster를 비활성화하면 다음 실행이 깨지므로 대상으로 삼지 않는다
    @Test
    @DisplayName("USE-9. MASTER 보호: 자기 자신 비활성화 422, 자기 자신 활성화 200, 다른 MASTER는 비활성화·재활성화 200")
    void use9_MASTER_계정_보호_및_경계_조건() {
        // 사전 조건: 테스트 MASTER의 ID, u를 MASTER로 올려 활성 MASTER를 둘 이상으로 만든다
        UUID masterId = masterId();
        s.changeRole(s.u, "MASTER");

        try {
            // 이렇게 하면 (1): MASTER가 자기 자신을 비활성화·활성화
            Response selfDeactivate = s.changeUserStatus(masterId, s.master, false);
            Response selfActivate = s.changeUserStatus(masterId, s.master, true);

            // 이렇게 돼야 한다 (1): 비활성화 422, 이미 활성이라 활성화는 200, 상태는 활성 그대로
            assertThat(selfDeactivate.status()).as("%s", selfDeactivate).isEqualTo(422);
            assertThat(selfDeactivate.errorCode()).isEqualTo("BUSINESS_RULE_VIOLATION");
            assertThat(selfActivate.status()).as("%s", selfActivate).isEqualTo(200);
            assertThat(isActive(masterId)).isTrue();

            // 이렇게 하면 (2): 다른 MASTER(u)를 비활성화한 뒤 재활성화
            Response deactivate = s.changeUserStatus(s.u.userId(), s.master, false);
            Response activate = s.changeUserStatus(s.u.userId(), s.master, true);

            // 이렇게 돼야 한다 (2): 둘 다 200
            assertStatusChanged(deactivate, false);
            assertStatusChanged(activate, true);
        } finally {
            restoreToUser(s.u);
        }
    }

    @Test
    @DisplayName("USE-10. 상태 변경 입력값·대상: active 누락·형식 오류·잘못된 ID 400, 없는 회원·삭제된 회원 404")
    void use10_상태_변경_입력값_및_대상() {
        // 이렇게 하면 (1): 잘못된 입력으로 u의 상태 변경
        String path = statusPath(s.u.userId());
        Response missingActive = Gateway.patch(path, s.master, Map.of());
        Response notBoolean = Gateway.patch(path, s.master, Map.of("active", "abc"));
        Response badId = Gateway.patch("/api/v1/users/not-a-uuid/status", s.master, Map.of("active", false));

        // 이렇게 돼야 한다 (1): 모두 400, u는 활성 그대로
        assertThat(missingActive.status()).as("%s", missingActive).isEqualTo(400);
        assertThat(missingActive.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(notBoolean.status()).as("%s", notBoolean).isEqualTo(400);
        assertThat(notBoolean.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(badId.status()).as("%s", badId).isEqualTo(400);
        assertThat(badId.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(badId.errorFields()).containsExactly("userId");
        assertThat(isActive(s.u.userId())).isTrue();

        // 이렇게 하면 (2): 없는 회원, 삭제한 u의 상태 변경
        Response unknown = s.changeUserStatus(UUID.randomUUID(), s.master, false);
        Gateway.delete(userPath(s.u.userId()), s.master).expect(200);
        Response deleted = s.changeUserStatus(s.u.userId(), s.master, false);

        // 이렇게 돼야 한다 (2): 둘 다 404
        assertThat(unknown.status()).as("%s", unknown).isEqualTo(404);
        assertThat(unknown.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(deleted.status()).as("%s", deleted).isEqualTo(404);
        assertThat(deleted.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("USE-11. 업체 관리자 비활성화: 200, 로그인 403, 목록에 active=false로 나옴, 재활성화 후 로그인 200")
    void use11_업체_관리자_비활성화_및_목록_조회() {
        // 이렇게 하면 (1): 업체 A 관리자 a를 비활성화 (비활성화는 진행 중인 배송만 확인하므로 업체 관리자도 막히지 않는다)
        Response deactivate = s.changeUserStatus(s.a.userId(), s.master, false);

        // 이렇게 돼야 한다 (1): 200, a는 로그인 403
        assertStatusChanged(deactivate, false);
        assertThat(s.a.tryLogin().status()).isEqualTo(403);

        // 이렇게 하면 (2): COMPANY 회원 목록에서 a를 검색
        JsonNode items = Gateway.get("/api/v1/users?role=COMPANY&username=" + s.a.username(), s.master)
                .expect(200).data().path("items");

        // 이렇게 돼야 한다 (2): 비활성 회원도 목록에서 빠지지 않고 active=false로 나온다
        assertThat(items).hasSize(1);
        JsonNode found = items.get(0);
        assertThat(found.path("username").asText()).isEqualTo(s.a.username());
        assertThat(found.path("companyId").asText()).isEqualTo(s.companyA.toString());
        assertThat(found.path("active").asBoolean()).isFalse();

        // 이렇게 하면 (3): a를 재활성화
        s.activateUser(s.a);

        // 이렇게 돼야 한다 (3): 다시 로그인된다
        assertThat(s.a.tryLogin().status()).isEqualTo(200);
    }

    // ---- 요청 ----

    // 개발 DB를 함께 쓰므로 테스트가 실패해도 MASTER로 올린 회원을 활성 USER로 되돌려 남기지 않는다
    private void restoreToUser(Member member) {
        s.activateUser(member);
        s.changeRole(member, "USER");
    }

    private boolean isActive(UUID userId) {
        return s.userDetail(userId).path("active").asBoolean();
    }

    // 테스트 MASTER(Accounts와 같은 계정)의 회원 ID. username 검색은 부분 일치라 정확히 같은 것을 고른다
    private UUID masterId() {
        String username = System.getProperty("it.masterUsername", "itmaster");
        JsonNode items = Gateway.get("/api/v1/users?role=MASTER&username=" + username + "&size=100", s.master)
                .expect(200).data().path("items");
        for (JsonNode item : items) {
            if (item.path("username").asText().equals(username)) {
                return UUID.fromString(item.path("userId").asText());
            }
        }
        throw new AssertionError("테스트 MASTER를 목록에서 찾지 못했습니다: " + username);
    }

    // ---- 확인 ----

    // 상태 변경이 200이고 응답의 active가 기대값인지 확인한다
    private void assertStatusChanged(Response response, boolean expectedActive) {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("active").asBoolean()).as("%s", response).isEqualTo(expectedActive);
    }

    private static String userPath(UUID userId) {
        return "/api/v1/users/" + userId;
    }

    private static String statusPath(UUID userId) {
        return userPath(userId) + "/status";
    }
}
