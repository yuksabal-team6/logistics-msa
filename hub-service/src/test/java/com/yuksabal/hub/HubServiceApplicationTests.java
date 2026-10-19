package com.yuksabal.hub;

import com.yuksabal.hub.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

// 로컬 개발 DB 대신 테스트용 PostgreSQL 컨테이너로 컨텍스트를 띄운다 (schema.sql·data.sql 실행까지 확인)
class HubServiceApplicationTests extends IntegrationTestSupport {

	@Test
	void contextLoads() {
	}

}
