package com.yuksabal.user.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

// initial-master.* ← 환경변수 INITIAL_MASTER_USERNAME·PASSWORD·EMAIL·SLACK_ID (로컬은 application-secret.yml 가능)
// 값이 없어도 기동은 실패시키지 않는다 (초기 MASTER 생성만 건너뛴다). toString에 비밀번호를 넣지 않는다.
@ConfigurationProperties(prefix = "initial-master")
public record InitialMasterProperties(String username, String password, String email, String slackId) {

    // 비어 있는 값의 환경변수 이름 (로그 안내용, 값은 담지 않는다)
    public List<String> missingVariables() {
        List<String> missing = new ArrayList<>();
        if (isBlank(username)) {
            missing.add("INITIAL_MASTER_USERNAME");
        }
        if (isBlank(password)) {
            missing.add("INITIAL_MASTER_PASSWORD");
        }
        if (isBlank(email)) {
            missing.add("INITIAL_MASTER_EMAIL");
        }
        if (isBlank(slackId)) {
            missing.add("INITIAL_MASTER_SLACK_ID");
        }
        return missing;
    }

    @Override
    public String toString() {
        return "InitialMasterProperties[username=" + username + ", password=****, email=" + email
                + ", slackId=" + slackId + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
