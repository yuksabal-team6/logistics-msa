package com.yuksabal.delivery;

import com.yuksabal.delivery.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

// PostgreSQL 컨테이너 + test 프로필(Eureka 끔)로 컨텍스트를 띄운다. 개발 DB(localhost:5432)에 붙지 않는다
// Docker가 없으면 건너뛴다 (IntegrationTestSupport)
class DeliveryServiceApplicationTests extends IntegrationTestSupport {

	@Test
	void contextLoads() {
	}

}
