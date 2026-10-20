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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private static final String ME = "/api/v1/auth/me";
    private static final String USERS = "/api/v1/users";

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
    @DisplayName("USE-2. 정보 조회·역할별 권한: 토큰 없음 401, 내 정보 200(비밀번호 없음), 본인 상세 200·다른 회원 상세 403, 목록은 MASTER만 200")
    void use2_사용자_정보_조회_및_역할별_권한() {
        // 사전 조건: u(USER)·a(COMPANY)의 토큰
        String userToken = s.u.token();
        String companyToken = s.a.token();

        // 이렇게 하면 (1): 토큰 없이, u의 토큰으로 내 정보 조회
        Response noToken = Gateway.get(ME, null);
        Response me = Gateway.get(ME, userToken);

        // 이렇게 돼야 한다 (1): 토큰 없음은 게이트웨이가 401, 내 정보는 200이고 비밀번호(해시)는 담지 않는다
        assertThat(noToken.status()).as("%s", noToken).isEqualTo(401);
        assertThat(noToken.errorCode()).isEqualTo("UNAUTHORIZED");
        assertThat(me.status()).as("%s", me).isEqualTo(200);
        assertThat(me.data().path("userId").asText()).isEqualTo(s.u.userId().toString());
        assertThat(me.data().path("username").asText()).isEqualTo(s.u.username());
        assertThat(me.data().path("role").asText()).isEqualTo("USER");
        assertThat(fieldNames(me.data())).noneMatch(field -> field.toLowerCase().contains("password"));

        // 이렇게 하면 (2): u가 본인, 다른 회원 b, 없는 회원의 상세 조회
        Response self = Gateway.get(userPath(s.u.userId()), userToken);
        Response other = Gateway.get(userPath(s.b.userId()), userToken);
        Response unknown = Gateway.get(userPath(UUID.randomUUID()), userToken);

        // 이렇게 돼야 한다 (2): 본인 200, 다른 회원은 있든 없든 403 (회원 ID가 있는지 드러내지 않는다)
        assertThat(self.status()).as("%s", self).isEqualTo(200);
        assertThat(self.data().path("userId").asText()).isEqualTo(s.u.userId().toString());
        assertThat(other.status()).as("%s", other).isEqualTo(403);
        assertThat(other.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(unknown.status()).as("%s", unknown).isEqualTo(403);
        assertThat(unknown.errorCode()).isEqualTo("FORBIDDEN");

        // 이렇게 하면 (3): USER(u)·COMPANY(a)·MASTER가 회원 목록 조회 (MASTER는 u의 아이디로 검색)
        Response listByUser = Gateway.get(USERS, userToken);
        Response listByCompany = Gateway.get(USERS, companyToken);
        Response listByMaster = Gateway.get(USERS + "?username=" + s.u.username(), s.master);

        // 이렇게 돼야 한다 (3): MASTER가 아니면 403, MASTER는 200이고 u가 나온다
        assertThat(listByUser.status()).as("%s", listByUser).isEqualTo(403);
        assertThat(listByUser.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(listByCompany.status()).as("%s", listByCompany).isEqualTo(403);
        assertThat(listByCompany.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(listByMaster.status()).as("%s", listByMaster).isEqualTo(200);
        JsonNode items = listByMaster.data().path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("userId").asText()).isEqualTo(s.u.userId().toString());
    }

    @Test
    @DisplayName("USE-3. 상태·역할 변경과 탈퇴 보호: 역할 변경 200·예전 토큰 403, 자기 자신 역할 변경·삭제 422, 업체 관리자 역할 변경 422, 삭제 후 로그인 401·조회 404")
    void use3_사용자_상태_역할_변경_및_탈퇴_보호() {
        // 사전 조건: 역할을 바꾸기 전에 u의 토큰을 받아 둔다
        String tokenBeforeRoleChange = s.u.token();

        // 이렇게 하면 (1): MASTER가 u의 역할을 DELIVERY로 변경한 뒤, 예전 토큰과 새로 받은 토큰으로 u 본인 상세 조회
        Response changeRole = Gateway.patch(rolePath(s.u.userId()), s.master, Map.of("role", "DELIVERY"));
        Response withOldToken = Gateway.get(userPath(s.u.userId()), tokenBeforeRoleChange);
        Response withNewToken = Gateway.get(userPath(s.u.userId()), s.u.token());

        // 이렇게 돼야 한다 (1): 변경 200 / 예전 토큰은 역할(USER)이 DB 역할(DELIVERY)과 달라 403, 새 토큰은 200
        assertThat(changeRole.status()).as("%s", changeRole).isEqualTo(200);
        assertThat(changeRole.data().path("role").asText()).isEqualTo("DELIVERY");
        assertThat(withOldToken.status()).as("%s", withOldToken).isEqualTo(403);
        assertThat(withOldToken.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(withNewToken.status()).as("%s", withNewToken).isEqualTo(200);
        assertThat(withNewToken.data().path("role").asText()).isEqualTo("DELIVERY");

        // 이렇게 하면 (2): MASTER가 자기 자신의 역할을 바꾸거나 자기 자신을 삭제
        // itmaster를 대상으로 하면 기대와 달리 성공했을 때 다음 실행이 깨지므로, v를 MASTER로 올려 v의 토큰으로 요청한다
        Member v = s.user("v");
        s.changeRole(v, "MASTER");
        try {
            String masterV = v.token();
            Response selfRole = Gateway.patch(rolePath(v.userId()), masterV, Map.of("role", "USER"));
            Response selfDelete = Gateway.delete(userPath(v.userId()), masterV);

            // 이렇게 돼야 한다 (2): 둘 다 422, v는 활성 MASTER 그대로
            assertThat(selfRole.status()).as("%s", selfRole).isEqualTo(422);
            assertThat(selfRole.errorCode()).isEqualTo("BUSINESS_RULE_VIOLATION");
            assertThat(selfRole.errorMessage()).isEqualTo("자기 자신의 역할은 변경할 수 없습니다.");
            assertThat(selfDelete.status()).as("%s", selfDelete).isEqualTo(422);
            assertThat(selfDelete.errorCode()).isEqualTo("BUSINESS_RULE_VIOLATION");
            assertThat(selfDelete.errorMessage()).isEqualTo("자기 자신은 삭제할 수 없습니다.");
            JsonNode afterSelf = s.userDetail(v.userId());
            assertThat(afterSelf.path("role").asText()).isEqualTo("MASTER");
            assertThat(afterSelf.path("active").asBoolean()).isTrue();
        } finally {
            restoreToUser(v);
        }

        // 이렇게 하면 (3): 업체 A 관리자 a의 역할을 USER로 변경
        Response ownerRole = Gateway.patch(rolePath(s.a.userId()), s.master, Map.of("role", "USER"));

        // 이렇게 돼야 한다 (3): user가 company 내부 API로 a가 관리하는 업체를 확인해 422, a는 COMPANY 그대로
        // (업체 관리자 a를 삭제하면 409인 경우는 CMP-17에서 확인한다)
        assertThat(ownerRole.status()).as("%s", ownerRole).isEqualTo(422);
        assertThat(ownerRole.errorCode()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(ownerRole.errorMessage()).isEqualTo("업체 관리자를 먼저 변경해 주세요.");
        assertThat(s.userDetail(s.a.userId()).path("role").asText()).isEqualTo("COMPANY");

        // 이렇게 하면 (4): 진행 중 업무가 없는 u를 삭제한 뒤 로그인, 조회, 다시 삭제
        Response delete = Gateway.delete(userPath(s.u.userId()), s.master);
        Response login = s.u.tryLogin();
        Response detail = Gateway.get(userPath(s.u.userId()), s.master);
        Response deleteAgain = Gateway.delete(userPath(s.u.userId()), s.master);

        // 이렇게 돼야 한다 (4): 삭제 200 / 로그인은 없는 아이디와 같은 401 / 조회·재삭제 404
        assertThat(delete.status()).as("%s", delete).isEqualTo(200);
        assertThat(login.status()).as("%s", login).isEqualTo(401);
        assertThat(login.errorCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(detail.status()).as("%s", detail).isEqualTo(404);
        assertThat(detail.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(deleteAgain.status()).as("%s", deleteAgain).isEqualTo(404);
        assertThat(deleteAgain.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    // 의존 서비스 장애(타임아웃·5xx → 502, 확인 구현체 없음 → 503)는 실행 중인 서비스를 내려야 해서 여기서 재현하지 않는다
    // user-service 테스트(UserDeleteActiveWorkIntegrationTest, CompanyOwnerProtectionIntegrationTest, UserAdminServiceTest)에서 확인한다
    @Test
    @DisplayName("USE-4. 내부 회원 조회: 게이트웨이로는 401·404, 다른 서비스는 내부 조회로 탈퇴 회원 주문 404·비활성/탈퇴 배송 기사 지정 422/404")
    void use4_내부_사용자_조회_및_의존_서비스_장애_대응() {
        // 사전 조건: v의 주문 O와 배송 D(READY), 비활성 배송 기사 d1, 삭제한 배송 기사 d2
        Member v = s.user("v");
        Response order = s.order(v.token(), 1).expect(201);
        String manager = "/api/v1/deliveries/" + order.uuid("deliveryId") + "/manager";
        Member d1 = s.deliveryDriver("d");
        s.deactivateUser(d1);
        Member d2 = s.deliveryDriver("e");
        Gateway.delete(userPath(d2.userId()), s.master).expect(200);

        // 이렇게 하면 (1): 게이트웨이로 내부 회원 조회 API를 토큰 없이, MASTER 토큰으로 호출
        String internal = "/internal/users/" + s.u.userId();
        Response internalNoToken = Gateway.get(internal, null);
        Response internalByMaster = Gateway.get(internal, s.master);

        // 이렇게 돼야 한다 (1): 인증이 먼저라 토큰 없음 401, 토큰이 있어도 /internal/**은 라우팅하지 않아 404
        assertThat(internalNoToken.status()).as("%s", internalNoToken).isEqualTo(401);
        assertThat(internalByMaster.status()).as("%s", internalByMaster).isEqualTo(404);
        assertThat(internalByMaster.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");

        // 이렇게 하면 (2): u의 토큰을 받아 둔 뒤 u를 삭제하고, 그 토큰으로 주문
        // (게이트웨이는 토큰만 보므로 통과시키고, order가 user 내부 API로 주문자를 다시 확인한다. 비활성 회원 403은 ORD-16)
        String tokenBeforeDelete = s.u.token();
        Gateway.delete(userPath(s.u.userId()), s.master).expect(200);
        int stockBefore = s.stockOfP();
        Response deletedOrder = s.order(tokenBeforeDelete, 1);

        // 이렇게 돼야 한다 (2): user의 404가 그대로 전달되고, 주문자 확인이 재고 차감 전이라 재고 그대로
        assertThat(deletedOrder.status()).as("%s", deletedOrder).isEqualTo(404);
        assertThat(deletedOrder.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(s.stockOfP()).isEqualTo(stockBefore);

        // 이렇게 하면 (3): MASTER가 D의 배송 담당자를 비활성 d1, 삭제한 d2로 지정
        // (delivery가 user 내부 API의 active·role을 보고 판단한다. USER 역할 회원 422는 DLV-5)
        Response inactiveManager = Gateway.patch(manager, s.master, Map.of("deliveryManagerId", d1.userId()));
        Response deletedManager = Gateway.patch(manager, s.master, Map.of("deliveryManagerId", d2.userId()));

        // 이렇게 돼야 한다 (3): 비활성 422, 탈퇴 404(field는 요청 필드 deliveryManagerId), D에 담당자 없음
        assertThat(inactiveManager.status()).as("%s", inactiveManager).isEqualTo(422);
        assertThat(inactiveManager.errorCode()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(inactiveManager.errorMessage()).isEqualTo("비활성화된 회원은 배송 담당자로 지정할 수 없습니다.");
        assertThat(deletedManager.status()).as("%s", deletedManager).isEqualTo(404);
        assertThat(deletedManager.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(deletedManager.errorFields()).containsExactly("deliveryManagerId");
        assertThat(s.delivery(order.uuid("deliveryId")).hasNonNull("deliveryManagerId")).isFalse();
    }

    // 비활성화·DELIVERY 역할 변경은 먼저 비활성으로 저장한 뒤 진행 중 배송을 확인하고, 막히면 원래 상태로 되돌린다
    // 409 응답 자체는 DLV-8에서 확인하므로 여기서는 되돌린 뒤의 상태(로그인·기존 토큰·역할)와 업무가 끝난 뒤의 비활성화를 본다
    // 확인 실패(502) 후 되돌리기는 delivery를 내려야 해서 user-service 테스트(UserActiveWorkIntegrationTest)에서 확인한다
    @Test
    @DisplayName("USE-5. 비활성화 실패 시 상태 복구: 진행 중 배송이 있어 409면 활성·DELIVERY 그대로, 로그인·기존 토큰 200, 배송 완료 후 비활성화 200")
    void use5_사용자_비활성화_실패_시_상태_복구() {
        // 사전 조건: v의 주문 O와 배송 D(READY), 배송 기사 d1을 D의 담당자로 지정, d1의 토큰을 받아 둔다
        Member v = s.user("v");
        Response order = s.order(v.token(), 1).expect(201);
        UUID deliveryId = order.uuid("deliveryId");
        Member d1 = s.deliveryDriver("d");
        Gateway.patch("/api/v1/deliveries/" + deliveryId + "/manager", s.master,
                Map.of("deliveryManagerId", d1.userId())).expect(200);
        String driverToken = d1.token();

        // 이렇게 하면 (1): MASTER가 d1을 비활성화, d1의 역할을 USER로 변경
        Response deactivate = s.changeUserStatus(d1.userId(), s.master, false);
        Response changeRole = Gateway.patch(rolePath(d1.userId()), s.master, Map.of("role", "USER"));

        // 이렇게 돼야 한다 (1): 둘 다 409
        assertThat(deactivate.status()).as("%s", deactivate).isEqualTo(409);
        assertThat(deactivate.errorCode()).isEqualTo("USER_HAS_ACTIVE_WORK");
        assertThat(changeRole.status()).as("%s", changeRole).isEqualTo(409);
        assertThat(changeRole.errorCode()).isEqualTo("USER_HAS_ACTIVE_WORK");

        // 이렇게 돼야 한다 (2): 확인하는 동안 잠깐 비활성이던 d1이 되돌려져 활성·DELIVERY, 로그인과 받아 둔 토큰 모두 200
        JsonNode restored = s.userDetail(d1.userId());
        assertThat(restored.path("active").asBoolean()).isTrue();
        assertThat(restored.path("role").asText()).isEqualTo("DELIVERY");
        assertThat(d1.tryLogin().status()).isEqualTo(200);
        Response withToken = Gateway.get(ME, driverToken);
        assertThat(withToken.status()).as("%s", withToken).isEqualTo(200);

        // 이렇게 하면 (3): D를 배송 완료한 뒤 d1을 다시 비활성화
        s.completeDelivery(deliveryId);
        Response deactivateAfter = s.changeUserStatus(d1.userId(), s.master, false);

        // 이렇게 돼야 한다 (3): 진행 중 배송이 없어 200, 이번에는 비활성으로 남아 로그인 403
        assertStatusChanged(deactivateAfter, false);
        assertThat(d1.tryLogin().status()).isEqualTo(403);
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

    // 차단은 user-service API에만 적용된다. 게이트웨이는 활성 여부를 보지 않아 다른 서비스는 토큰 만료(1시간)까지 통과시키고,
    // 주문처럼 내부 API로 회원을 다시 확인하는 곳만 막는다 (ORD-16)
    @Test
    @DisplayName("USE-7. 비활성 회원 JWT: 비활성화 전에 받은 토큰으로 내 정보 조회·수정·비밀번호 변경·상세 조회 모두 403(ACCOUNT_DISABLED), 정보 그대로")
    void use7_비활성_사용자_JWT_접근_제어() {
        // 사전 조건: u로 로그인해 토큰을 받아 둔 뒤 u를 비활성화
        // (노션은 DB의 is_active를 직접 바꾸지만 여기서는 상태 변경 API로 바꾼다. user-service가 요청마다 DB에서 활성 여부를 읽어 결과는 같다)
        String token = s.u.token();
        s.deactivateUser(s.u);

        // 이렇게 하면: 받아 둔 토큰으로 내 정보 조회, 내 정보 수정, 비밀번호 변경, 본인 상세 조회
        Response me = Gateway.get(ME, token);
        Response updateMe = Gateway.patch(ME, token, Map.of("slackId", "it-changed-" + s.suffix));
        Response changePassword = Gateway.patch(ME + "/password", token,
                Map.of("currentPassword", Scenario.PASSWORD, "newPassword", "ItUser34!"));
        Response detail = Gateway.get(userPath(s.u.userId()), token);

        // 이렇게 돼야 한다
        // 1~2. 게이트웨이는 유효한 토큰이라 통과시키고, user-service가 DB의 활성 여부를 보고 모두 403
        for (Response r : List.of(me, updateMe, changePassword, detail)) {
            assertThat(r.status()).as("%s", r).isEqualTo(403);
            assertThat(r.errorCode()).as("%s", r).isEqualTo("ACCOUNT_DISABLED");
            assertThat(r.errorMessage()).as("%s", r).isEqualTo("비활성화된 계정입니다.");
        }
        // 3. 거절된 수정·비밀번호 변경은 반영되지 않는다: slackId 그대로, 재활성화 후 원래 비밀번호로 로그인 200
        assertThat(s.userDetail(s.u.userId()).path("slackId").asText()).isEqualTo("it-" + s.u.username());
        s.activateUser(s.u);
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

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String userPath(UUID userId) {
        return USERS + "/" + userId;
    }

    private static String statusPath(UUID userId) {
        return userPath(userId) + "/status";
    }

    private static String rolePath(UUID userId) {
        return userPath(userId) + "/role";
    }
}
