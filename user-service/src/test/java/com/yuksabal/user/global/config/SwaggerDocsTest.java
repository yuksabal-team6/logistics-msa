package com.yuksabal.user.global.config;

import com.yuksabal.user.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 게이트웨이 통합 Swagger가 읽는 /v3/api-docs가 정상 생성되고, 회원 API가 모두 문서에 있는지 확인한다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SwaggerDocsTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void API_문서가_생성되고_회원_관리_API가_포함된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/users'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users/{userId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users/{userId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users/{userId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users/{userId}/role'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users/{userId}/status'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/internal/users/{userId}'].get").exists());
    }
}
