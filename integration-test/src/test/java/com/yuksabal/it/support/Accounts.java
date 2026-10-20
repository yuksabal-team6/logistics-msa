package com.yuksabal.it.support;

import java.util.Map;

// 로그인. MASTER는 docker-compose.it.yml의 초기 MASTER 계정 (다른 계정이면 -Dit.masterUsername·-Dit.masterPassword)
public final class Accounts {

    private static final String MASTER_USERNAME = System.getProperty("it.masterUsername", "itmaster");
    private static final String MASTER_PASSWORD = System.getProperty("it.masterPassword", "ItMaster1!");

    private static String masterToken;

    private Accounts() {
    }

    // 테스트 전체에서 한 번만 로그인한다 (토큰 유효 시간 안에 끝난다)
    public static synchronized String masterToken() {
        if (masterToken == null) {
            masterToken = login(MASTER_USERNAME, MASTER_PASSWORD);
        }
        return masterToken;
    }

    public static String login(String username, String password) {
        Gateway.Response response = tryLogin(username, password);
        if (response.status() != 200) {
            throw new AssertionError("로그인 실패 (" + username + "). MASTER라면 docker-compose.it.yml로 띄웠는지, "
                    + "이미 다른 MASTER가 있어 초기 MASTER가 생성되지 않았는지 확인해 주세요: " + response);
        }
        return response.data().path("accessToken").asText();
    }

    // 로그인 실패 응답(401·403)을 확인할 때 쓴다. login과 달리 200이 아니어도 멈추지 않고 Response를 돌려준다
    public static Gateway.Response tryLogin(String username, String password) {
        return Gateway.post("/api/v1/auth/login", null, Map.of("username", username, "password", password));
    }
}
