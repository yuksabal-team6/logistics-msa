package com.yuksabal.hub.inventory.repository;

import com.yuksabal.hub.global.config.JpaAuditingConfig;
import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.hub.hub.repository.HubRepository;
import com.yuksabal.hub.inventory.entity.Inventory;
import com.yuksabal.hub.inventory.entity.InventoryChangeType;
import com.yuksabal.hub.inventory.entity.InventoryLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static com.yuksabal.hub.inventory.repository.InventoryLogSpecification.search;
import static org.assertj.core.api.Assertions.assertThat;

// 허브 조건은 재고와 조인해야 해서 실제 DB가 해석해야 확인할 수 있다. 로컬 Postgres로 실행한다 (HubSpecificationTest와 같은 DB)
// 테스트마다 롤백되어 추가한 허브·재고·로그는 남지 않는다. 다른 로그와 섞이지 않게 항상 이 테스트의 ID로 조건을 건다
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class InventoryLogSpecificationTest {

    private static final UUID ORDER_ID = UUID.randomUUID();

    @Autowired
    private HubRepository hubRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryLogRepository inventoryLogRepository;

    private Hub hubA;
    private Inventory inventoryA1;
    private Inventory inventoryA2;
    private Inventory inventoryB;

    @BeforeEach
    void setUp() {
        hubA = hubRepository.save(Hub.builder().hubName("로그 테스트 허브 A").address("테스트 주소").build());
        Hub hubB = hubRepository.save(Hub.builder().hubName("로그 테스트 허브 B").address("테스트 주소").build());
        inventoryA1 = inventoryRepository.save(inventory(hubA));
        inventoryA2 = inventoryRepository.save(inventory(hubA));
        inventoryB = inventoryRepository.save(inventory(hubB));

        inventoryLogRepository.saveAll(List.of(
                log(inventoryA1, InventoryChangeType.INBOUND, null),
                log(inventoryA1, InventoryChangeType.DECREASE, ORDER_ID),
                log(inventoryA2, InventoryChangeType.INBOUND, null),
                log(inventoryB, InventoryChangeType.INBOUND, null)));
    }

    @Test
    void 재고로_검색하면_그_재고의_로그만_찾는다() {
        assertThat(find(inventoryA1.getId(), null, null, null)).hasSize(2)
                .allMatch(log -> log.getInventory().getId().equals(inventoryA1.getId()));
    }

    @Test
    void 허브로_검색하면_그_허브의_모든_재고_로그를_찾는다() {
        assertThat(find(null, hubA.getId(), null, null)).hasSize(3)
                .allMatch(log -> log.getInventory().getHub().getId().equals(hubA.getId()));
    }

    @Test
    void 주문으로_검색하면_그_주문의_로그만_찾는다() {
        assertThat(find(null, null, ORDER_ID, null))
                .singleElement()
                .satisfies(log -> assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.DECREASE));
    }

    @Test
    void 조건을_함께_쓰면_모두_만족하는_로그만_찾는다() {
        assertThat(find(null, hubA.getId(), null, InventoryChangeType.INBOUND)).hasSize(2)
                .allMatch(log -> log.getChangeType() == InventoryChangeType.INBOUND);
    }

    @Test
    void 없는_ID로_검색하면_빈_목록이다() {
        assertThat(find(UUID.randomUUID(), null, null, null)).isEmpty();
    }

    private List<InventoryLog> find(UUID inventoryId, UUID hubId, UUID orderId, InventoryChangeType changeType) {
        return inventoryLogRepository.findAll(search(inventoryId, hubId, orderId, changeType), PageRequest.of(0, 20))
                .getContent();
    }

    private Inventory inventory(Hub hub) {
        return Inventory.builder().hub(hub).productId(UUID.randomUUID()).companyId(UUID.randomUUID()).quantity(10).build();
    }

    private InventoryLog log(Inventory inventory, InventoryChangeType changeType, UUID orderId) {
        return InventoryLog.builder().inventory(inventory).changeType(changeType).orderId(orderId).quantity(1).build();
    }
}
