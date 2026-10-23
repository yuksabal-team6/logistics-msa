package com.yuksabal.hub.inventory.domain;

import com.yuksabal.hub.global.config.JpaAuditingConfig;
import com.yuksabal.hub.hub.domain.Hub;
import com.yuksabal.hub.hub.domain.HubRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 재고 로그는 한 번 남기면 바뀌지 않는다 (#280). 변경 감지 UPDATE가 나가는지는 실제 DB로 flush 해야 알 수 있다
// 로그에는 값을 바꾸는 메서드가 없어서, 코드 실수로 값이 바뀐 상황을 리플렉션으로 만든다. 테스트마다 롤백된다
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class InventoryLogImmutabilityTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private HubRepository hubRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryLogRepository inventoryLogRepository;

    private Hub hub;
    private Inventory inventory;
    private InventoryLog log;

    @BeforeEach
    void setUp() {
        hub = hubRepository.save(Hub.builder().hubName("로그 불변 테스트 허브").address("테스트 주소").build());
        inventory = inventoryRepository.save(inventory(10));
        log = inventoryLogRepository.saveAndFlush(InventoryLog.inbound(inventory, 10));
    }

    @Test
    void 저장한_로그의_수량을_바꿔도_DB에는_반영되지_않는다() {
        ReflectionTestUtils.setField(log, "quantity", 99);

        em.flush();
        em.clear();

        assertThat(em.find(InventoryLog.class, log.getId()).getQuantity()).isEqualTo(10);
    }

    @Test
    void 저장한_로그가_가리키는_재고를_바꿔도_DB에는_반영되지_않는다() {
        Inventory other = inventoryRepository.save(inventory(5));
        ReflectionTestUtils.setField(log, "inventory", other);

        em.flush();
        em.clear();

        assertThat(em.find(InventoryLog.class, log.getId()).getInventory().getId()).isEqualTo(inventory.getId());
    }

    private Inventory inventory(int quantity) {
        return Inventory.builder().hubId(hub.getId()).productId(UUID.randomUUID()).companyId(UUID.randomUUID()).quantity(quantity).build();
    }
}
