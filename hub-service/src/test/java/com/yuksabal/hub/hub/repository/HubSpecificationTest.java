package com.yuksabal.hub.hub.repository;

import com.yuksabal.hub.global.config.JpaAuditingConfig;
import com.yuksabal.hub.hub.entity.Hub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static com.yuksabal.hub.hub.repository.HubSpecification.keywordContains;
import static org.assertj.core.api.Assertions.assertThat;

// LIKE 이스케이프는 DB가 실제로 해석해야 확인할 수 있어 로컬 Postgres로 실행한다 (HubServiceApplicationTests와 같은 DB)
// 테스트마다 롤백되어 추가한 허브는 남지 않는다. 초기 허브 17개(data.sql)에는 % _ 가 없다
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class HubSpecificationTest {

    @Autowired
    private HubRepository hubRepository;

    @BeforeEach
    void setUp() {
        hubRepository.saveAll(List.of(
                hub("할인 10% 테스트 센터"),
                hub("고등어 10kg 테스트 센터"),
                hub("A_B 테스트 센터"),
                hub("AXB 테스트 센터")));
    }

    @Test
    void 퍼센트만_검색하면_전체가_아니라_퍼센트가_들어간_허브만_찾는다() {
        assertThat(search("%")).containsExactly("할인 10% 테스트 센터");
    }

    @Test
    void 퍼센트가_들어간_검색어는_글자_그대로_비교한다() {
        assertThat(search("10%")).containsExactly("할인 10% 테스트 센터");
    }

    @Test
    void 밑줄은_한_글자_와일드카드가_아니라_글자로_비교한다() {
        assertThat(search("A_B")).containsExactly("A_B 테스트 센터");
    }

    @Test
    void 일반_검색어는_허브명과_주소에서_부분_일치로_찾는다() {
        assertThat(search("서울")).contains("서울특별시 센터");
        assertThat(search("테스트 주소")).hasSize(4);
    }

    @Test
    void 검색어가_없거나_공백이면_조건_없이_전체를_조회한다() {
        long total = hubRepository.count();

        assertThat(search(null)).hasSize((int) total);
        assertThat(search("  ")).hasSize((int) total);
    }

    private List<String> search(String keyword) {
        return hubRepository.findAll(keywordContains(keyword), PageRequest.of(0, 100))
                .map(Hub::getHubName)
                .getContent();
    }

    private Hub hub(String hubName) {
        return Hub.builder().hubName(hubName).address("테스트 주소").build();
    }
}
