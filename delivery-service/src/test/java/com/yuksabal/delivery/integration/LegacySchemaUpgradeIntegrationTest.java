package com.yuksabal.delivery.integration;

import com.yuksabal.delivery.DeliveryServiceApplication;
import com.yuksabal.delivery.delivery.application.DeliveryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// #302 시나리오 7: 예전 모양(다섯 컬럼 NOT NULL) 테이블이 있는 DB에서 ddl-auto: update로 기동하면
// schema.sql의 DROP NOT NULL로 컬럼이 풀리고 표식을 저장할 수 있는지. 다시 실행해도(이미 nullable) 오류가 없는지
// DROP NOT NULL은 NOT NULL 컬럼이 있을 때만 실행된다(DO 블록). 실행 여부는 PostgreSQL 이벤트 트리거로 DDL을 기록해 센다
//
// 다른 통합 테스트(IntegrationTestSupport: 공유 컨테이너 + create-drop)와 분리한다
//  - 이 클래스 전용 컨테이너를 쓰고, @SpringBootTest 대신 앱을 직접 두 번 띄운다 (기동 순서를 테스트가 정해야 해서)
//  - 명령줄 인자(--)로 ddl-auto·sql.init.mode를 넘긴다. test 프로필(create-drop)보다 우선순위가 높다
//
// 예전 DDL을 만드는 방법: 1차 기동에서 Hibernate가 지금 엔티티로 테이블을 만들고(sql 초기화 끔), 다섯 컬럼에 SET NOT NULL을 건다
//  근거: #302 전 엔티티(git HEAD의 Delivery.java)와 지금 엔티티의 매핑 차이는 이 다섯 컬럼의 @Column(nullable = false)뿐이다
//  (address length 500, recipient_name length 100은 그대로). 그래서 "지금 DDL + 다섯 컬럼 NOT NULL" = 예전 엔티티가 만든 DDL
@Testcontainers(disabledWithoutDocker = true)
class LegacySchemaUpgradeIntegrationTest {

    private static final List<String> MARKER_NULL_COLUMNS = List.of(
            "departure_hub_id", "destination_hub_id", "destination_company_id", "address", "recipient_name");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Test
    void 예전_NOT_NULL_테이블에서_update로_기동하면_컬럼이_풀리고_표식을_저장한다() {
        // 1) 예전 모양 테이블 만들기
        try (ConfigurableApplicationContext legacy = start("create", "never")) {
            JdbcTemplate jdbc = legacy.getBean(JdbcTemplate.class);
            for (String column : MARKER_NULL_COLUMNS) {
                jdbc.execute("ALTER TABLE delivery_service.p_deliveries ALTER COLUMN " + column + " SET NOT NULL");
            }
            assertThat(nullability(jdbc)).as("예전 모양: 다섯 컬럼 NOT NULL").containsOnlyKeys(MARKER_NULL_COLUMNS)
                    .allSatisfy((column, nullable) -> assertThat(nullable).isEqualTo("NO"));
            installDdlLog(jdbc);   // 이 뒤의 ALTER TABLE을 기록한다 (예전 모양을 만든 SET NOT NULL은 제외)
            // 예전 DB에는 정상 배송이 있다. 업그레이드 뒤에도 그대로 남아야 한다
            jdbc.update("INSERT INTO delivery_service.p_deliveries (delivery_id, order_id, departure_hub_id, destination_hub_id,"
                            + " destination_company_id, address, recipient_name, delivery_status, created_at, version)"
                            + " VALUES (?, ?, ?, ?, ?, '부산시 해운대구', '수령인', 'READY', now(), 0)",
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        }

        // 2) 운영 설정과 같은 ddl-auto: update + sql.init.mode: always로 기동
        try (ConfigurableApplicationContext upgraded = start("update", "always")) {
            JdbcTemplate jdbc = upgraded.getBean(JdbcTemplate.class);
            Map<String, String> after = nullability(jdbc);
            System.out.println("[#302 결과] 시나리오=7, 기동 후 컬럼 nullable=" + after);
            assertThat(after).as("schema.sql DROP NOT NULL 뒤").allSatisfy((column, nullable) -> assertThat(nullable).isEqualTo("YES"));
            assertThat(dropNotNullCount(jdbc)).as("NOT NULL 컬럼이 있어 DROP NOT NULL 1번 실행").isEqualTo(1);

            UUID orderId = UUID.randomUUID();
            upgraded.getBean(DeliveryService.class).cancelDelivery(orderId);
            assertThat(jdbc.queryForList("SELECT delivery_status, departure_hub_id FROM delivery_service.p_deliveries WHERE order_id = ?", orderId))
                    .as("표식 저장").singleElement()
                    .satisfies(row -> {
                        assertThat(row.get("delivery_status")).isEqualTo("CANCELLED");
                        assertThat(row.get("departure_hub_id")).isNull();
                    });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_service.p_deliveries WHERE delivery_status = 'READY'", Integer.class))
                    .as("예전 정상 배송 유지").isEqualTo(1);

            // 3) schema.sql만 다시 실행: 모두 nullable이라 ALTER 없이 오류 없이 끝난다
            ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
            schema.setSqlScriptEncoding("UTF-8");
            assertThatCode(() -> schema.execute(upgraded.getBean(DataSource.class))).doesNotThrowAnyException();
            assertThat(nullability(jdbc)).allSatisfy((column, nullable) -> assertThat(nullable).isEqualTo("YES"));
            assertThat(dropNotNullCount(jdbc)).as("schema.sql 재실행: ALTER 안 함").isEqualTo(1);
        }

        // 4) 업그레이드 뒤 다시 기동 (운영의 재시작): DO 블록이 NOT NULL 컬럼이 없음을 보고 ALTER를 실행하지 않는다
        try (ConfigurableApplicationContext restarted = start("update", "always")) {
            JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
            System.out.println("[#302 결과] 시나리오=7, p_deliveries DDL 기록=" + ddlLog(jdbc));
            assertThat(dropNotNullCount(jdbc)).as("재기동: ALTER 안 함").isEqualTo(1);
            assertThat(nullability(jdbc)).allSatisfy((column, nullable) -> assertThat(nullable).isEqualTo("YES"));
        }
    }

    // ddl_command_end 이벤트 트리거: p_deliveries 등의 DDL마다 명령 태그·대상·실행한 최상위 문장(current_query)을 남긴다
    // DO 블록 안의 ALTER는 최상위 문장이 DO 블록이라 그 본문(DROP NOT NULL)으로 구분한다. 테스트 사용자는 슈퍼유저(컨테이너 기본)
    private static void installDdlLog(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE public.ddl_log (id serial PRIMARY KEY, command_tag text, object_identity text, query text)");
        jdbc.execute("""
                CREATE FUNCTION public.log_ddl() RETURNS event_trigger LANGUAGE plpgsql AS $$
                DECLARE r record;
                BEGIN
                    FOR r IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP
                        INSERT INTO public.ddl_log (command_tag, object_identity, query)
                        VALUES (r.command_tag, r.object_identity, current_query());
                    END LOOP;
                END
                $$""");
        jdbc.execute("CREATE EVENT TRIGGER log_ddl ON ddl_command_end EXECUTE FUNCTION public.log_ddl()");
    }

    // p_deliveries에 실행된 DROP NOT NULL ALTER 수
    private static int dropNotNullCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM public.ddl_log WHERE command_tag = 'ALTER TABLE'"
                + " AND object_identity = 'delivery_service.p_deliveries' AND query ILIKE '%DROP NOT NULL%'", Integer.class);
    }

    private static List<String> ddlLog(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT command_tag, object_identity, query ILIKE '%DROP NOT NULL%' AS drop_not_null"
                        + " FROM public.ddl_log WHERE object_identity LIKE 'delivery_service.p_deliveries%' ORDER BY id")
                .stream().map(Object::toString).toList();
    }

    // ddl·sql 초기화만 바꿔 앱을 띄운다. 나머지는 test 프로필(Eureka 끔) + 이 클래스의 컨테이너
    private static ConfigurableApplicationContext start(String ddlAuto, String sqlInitMode) {
        return new SpringApplicationBuilder(DeliveryServiceApplication.class)
                .profiles("test")
                .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=" + ddlAuto,
                        "--spring.sql.init.mode=" + sqlInitMode,
                        "--server.port=0");
    }

    // 다섯 컬럼의 is_nullable (YES/NO)
    private static Map<String, String> nullability(JdbcTemplate jdbc) {
        Map<String, String> result = new java.util.TreeMap<>();
        jdbc.queryForList("SELECT column_name, is_nullable FROM information_schema.columns"
                        + " WHERE table_schema = 'delivery_service' AND table_name = 'p_deliveries'")
                .stream()
                .filter(row -> MARKER_NULL_COLUMNS.contains((String) row.get("column_name")))
                .forEach(row -> result.put((String) row.get("column_name"), (String) row.get("is_nullable")));
        return result;
    }
}
