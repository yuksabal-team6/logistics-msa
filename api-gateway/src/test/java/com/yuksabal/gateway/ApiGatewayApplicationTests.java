package com.yuksabal.gateway;

import com.yuksabal.gateway.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 테스트 전용 무작위 키, Eureka 서버 없이 실행
@SpringBootTest(properties = "eureka.client.enabled=false")
class ApiGatewayApplicationTests {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
	}

	@Test
	void contextLoads() {
	}

}
