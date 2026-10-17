-- =====================================================================
-- hub-service 초기 데이터 (앱 시작 시마다 실행, 이미 있는 행은 건너뜀)
--
-- 허브 ID  : 00000000-0000-0000-0000-0000000000NN   (NN = 01 ~ 17, 아래 순서)
-- 경로 ID  : 00000000-0000-0000-00DD-0000000000AA   (DD = 출발 허브 번호, AA = 도착 허브 번호)
--            예) 서울(01) → 부산(04) = 00000000-0000-0000-0001-000000000004
-- 경로 계산: 직선거리(Haversine) × 1.3(도로 우회 계수), 평균 시속 60km
-- 좌표     : 주소 기준 근사값 (수백 m 오차 가능)
-- =====================================================================

-- 허브 17개
INSERT INTO hub_service.p_hubs (hub_id, hub_name, address, latitude, longitude, created_at)
SELECT v.hub_id::uuid, v.hub_name, v.address, v.latitude, v.longitude, now()
FROM (VALUES
    ('00000000-0000-0000-0000-000000000001', '서울특별시 센터',      '서울특별시 송파구 송파대로 55',            37.4779000, 127.1246000),
    ('00000000-0000-0000-0000-000000000002', '경기 북부 센터',       '경기도 고양시 덕양구 권율대로 570',        37.6522000, 126.8730000),
    ('00000000-0000-0000-0000-000000000003', '경기 남부 센터',       '경기도 이천시 덕평로 257-21',              37.1903000, 127.3753000),
    ('00000000-0000-0000-0000-000000000004', '부산광역시 센터',      '부산 동구 중앙대로 206',                   35.1176000, 129.0451000),
    ('00000000-0000-0000-0000-000000000005', '대구광역시 센터',      '대구 북구 태평로 161',                     35.8762000, 128.5962000),
    ('00000000-0000-0000-0000-000000000006', '인천광역시 센터',      '인천 남동구 정각로 29',                    37.4563000, 126.7052000),
    ('00000000-0000-0000-0000-000000000007', '광주광역시 센터',      '광주 서구 내방로 111',                     35.1600000, 126.8514000),
    ('00000000-0000-0000-0000-000000000008', '대전광역시 센터',      '대전 서구 둔산로 100',                     36.3504000, 127.3845000),
    ('00000000-0000-0000-0000-000000000009', '울산광역시 센터',      '울산 남구 중앙로 201',                     35.5384000, 129.3114000),
    ('00000000-0000-0000-0000-000000000010', '세종특별자치시 센터',  '세종특별자치시 한누리대로 2130',           36.4800000, 127.2890000),
    ('00000000-0000-0000-0000-000000000011', '강원특별자치도 센터',  '강원특별자치도 춘천시 중앙로 1',           37.8853000, 127.7298000),
    ('00000000-0000-0000-0000-000000000012', '충청북도 센터',        '충북 청주시 상당구 상당로 82',             36.6357000, 127.4912000),
    ('00000000-0000-0000-0000-000000000013', '충청남도 센터',        '충남 홍성군 홍북읍 충남대로 21',           36.6588000, 126.6728000),
    ('00000000-0000-0000-0000-000000000014', '전북특별자치도 센터',  '전북특별자치도 전주시 완산구 효자로 225',  35.8203000, 127.1088000),
    ('00000000-0000-0000-0000-000000000015', '전라남도 센터',        '전남 무안군 삼향읍 오룡길 1',              34.8161000, 126.4629000),
    ('00000000-0000-0000-0000-000000000016', '경상북도 센터',        '경북 안동시 풍천면 도청대로 455',          36.5760000, 128.5056000),
    ('00000000-0000-0000-0000-000000000017', '경상남도 센터',        '경남 창원시 의창구 중앙대로 300',          35.2383000, 128.6922000)
) AS v (hub_id, hub_name, address, latitude, longitude)
WHERE NOT EXISTS (
    SELECT 1 FROM hub_service.p_hubs h WHERE h.hub_id = v.hub_id::uuid
);

-- 허브 간 경로 272개 (17 × 16, 모든 허브 쌍 양방향 P2P)
INSERT INTO hub_service.p_hub_routes
    (hub_route_id, departure_hub_id, arrival_hub_id, distance_km, duration_minutes, created_at)
SELECT
    ('00000000-0000-0000-00' || right(d.hub_id::text, 2) || '-0000000000' || right(a.hub_id::text, 2))::uuid,
    d.hub_id,
    a.hub_id,
    round((x.km * 1.3)::numeric, 2),
    ceil(x.km * 1.3 / 60 * 60)::int,
    now()
FROM hub_service.p_hubs d
JOIN hub_service.p_hubs a ON a.hub_id <> d.hub_id
CROSS JOIN LATERAL (
    SELECT 2 * 6371 * asin(sqrt(
        power(sin(radians(a.latitude - d.latitude) / 2), 2)
        + cos(radians(d.latitude)) * cos(radians(a.latitude))
          * power(sin(radians(a.longitude - d.longitude) / 2), 2)
    )) AS km
) x
WHERE d.hub_id::text LIKE '00000000-0000-0000-0000-0000000000%'
  AND a.hub_id::text LIKE '00000000-0000-0000-0000-0000000000%'
  AND NOT EXISTS (
    SELECT 1 FROM hub_service.p_hub_routes r
    WHERE r.departure_hub_id = d.hub_id AND r.arrival_hub_id = a.hub_id
  );

-- 재고 로그 change_type CHECK 제약 (Hibernate가 enum으로 만든 제약)
-- ddl-auto update는 기존 CHECK 제약을 바꾸지 않아, InventoryChangeType에 값을 추가하면 여기서 다시 만든다
-- TODO 운영 DB 통합 시 제거하고 일회성 SQL로 한 번만 실행 (sql.init.mode=always라 시작할 때마다 테이블 잠금·전체 행 검사가 일어난다)
ALTER TABLE hub_service.p_inventory_logs DROP CONSTRAINT IF EXISTS p_inventory_logs_change_type_check;
ALTER TABLE hub_service.p_inventory_logs ADD CONSTRAINT p_inventory_logs_change_type_check
    CHECK (change_type IN ('DECREASE', 'RESTORE', 'INBOUND', 'ADJUST_INCREASE', 'ADJUST_DECREASE'));

-- 주문 재고 차감·복원 멱등성 키: 같은 주문의 같은 변경(DECREASE·RESTORE)은 한 번만 기록된다 (결정 #18)
-- 동시에 같은 요청이 들어오면 두 번째 로그 INSERT가 유니크 위반으로 실패해 그 트랜잭션의 재고 변경도 롤백된다
-- 입고·관리자 조정은 order_id가 null이라 대상이 아니다. ddl-auto는 조건부 인덱스를 만들 수 없어 직접 생성한다
CREATE UNIQUE INDEX IF NOT EXISTS uk_inventory_logs_order_id_change_type
    ON hub_service.p_inventory_logs (order_id, change_type)
    WHERE order_id IS NOT NULL;

-- 한 허브에 같은 상품의 활성 재고는 하나다. 주문 차감 UPDATE·조회가 허브 + 상품으로 한 행을 찾는 전제를 DB에서 보장한다
-- 업체 허브가 바뀌어 다른 허브에 같은 상품 재고가 생기는 경우는 허브가 달라 대상이 아니다
-- 활성 중복 행이 이미 있으면 인덱스 생성이 실패해 앱이 시작되지 않으므로, 중복을 먼저 정리해야 한다
CREATE UNIQUE INDEX IF NOT EXISTS uk_inventories_hub_id_product_id
    ON hub_service.p_inventories (hub_id, product_id)
    WHERE deleted_at IS NULL;
