# Gateway 사용자 정보 조회 캐싱(#296) 부하 테스트 결과

- 측정일: 2026-11-08
- 스크립트: [`auth-me.js`](auth-me.js) (k6, Docker `grafana/k6`)
- 브랜치: `test/298-performance-auth-hardening`

## 측정 조건

| 항목 | 값 |
|---|---|
| 대상 API | `GET /api/v1/auth/me` (Gateway JWT 검증 → user-service 역할·활성 상태 조회 → user-service `/me`) |
| 부하 | 동시 사용자(VU) 50, 30초, `constant-vus` |
| 워밍업 | 조건마다 Gateway 재기동 후 15초 부하로 워밍업, 이후 3회 측정 |
| 토큰 | setup에서 1회 로그인한 같은 회원의 Access Token을 모든 VU가 사용 |
| 환경 | 로컬 docker compose (Windows 11, Docker Desktop), Gateway `-Xmx192m -XX:+UseSerialGC` |
| 캐시 ON | 기본 설정 (`auth.user-status-cache.ttl: 5s`) |
| 캐시 OFF | Gateway 컨테이너에 `AUTH_USERSTATUSCACHE_TTL=0s` (ttl 0이면 캐시 끔) |

## 결과

| 조건 | 회차 | 처리량 (req/s) | 평균 | 중앙값 | p95 | p99 | 실패율 |
|---|---|---|---|---|---|---|---|
| 캐시 OFF | 1 | 1,024 | 48.3ms | 45.2ms | 83.1ms | 108.4ms | 0% |
| | 2 | 1,041 | 47.6ms | 44.7ms | 80.6ms | 105.4ms | 0% |
| | 3 | 1,053 | 47.0ms | 44.4ms | 79.2ms | 100.6ms | 0% |
| | **평균** | **1,039** | **47.6ms** | **44.8ms** | **81.0ms** | **104.8ms** | **0%** |
| 캐시 ON (TTL 5s) | 1 | 1,563 | 31.6ms | 29.1ms | 58.2ms | 80.2ms | 0% |
| | 2 | 1,499 | 32.9ms | 30.1ms | 61.8ms | 81.8ms | 0% |
| | 3 | 1,524 | 32.4ms | 29.8ms | 59.7ms | 78.2ms | 0% |
| | **평균** | **1,529** | **32.3ms** | **29.7ms** | **59.9ms** | **80.1ms** | **0%** |
| **변화 (ON vs OFF)** | | **+47%** | **−32%** | **−34%** | **−26%** | **−24%** | |

## 해석

- 캐시를 켜면 TTL(5초) 안에서는 요청당 user-service 호출이 2회 → 1회로 줄어 처리량이 약 47% 늘고 p95 지연이 약 26% 줄었다.
- 회차 간 편차(1~4%)보다 조건 간 차이가 훨씬 커서 캐시 효과로 볼 수 있다.
- 모든 요청이 같은 회원 토큰을 쓰므로 캐시 적중률이 거의 100%인 조건이다. 실제처럼 여러 회원이 섞이면 개선 폭은 더 작을 수 있다.
- 측정 때 Gateway만 최신 코드로 재빌드했고 user-service 컨테이너는 10-20 빌드 이미지였다. ON/OFF 모두 같은 user-service라 비교에는 영향이 없다.
- 로컬 단일 머신 측정이라 절대 수치는 환경에 따라 달라진다. 비교는 같은 환경의 ON/OFF 상대값으로만 본다.

## 재현 방법

```bash
# 1) Gateway jar 빌드 (Dockerfile은 미리 빌드된 jar를 복사만 하므로 필수)
./gradlew :api-gateway:bootJar

# 2) 이미지 재빌드 후 기동
docker compose build api-gateway
docker compose up -d api-gateway

# 3) 측정 (활성 회원 계정 필요)
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 \
  -e USERNAME=<아이디> -e PASSWORD=<비밀번호> \
  grafana/k6 run - < api-gateway/loadtest/auth-me.js
```

캐시 OFF 측정은 레포 파일을 고치지 않고 override 파일로 Gateway만 재기동한다.

```yaml
# nocache.yml (레포 밖에 둔다)
services:
  api-gateway:
    environment:
      AUTH_USERSTATUSCACHE_TTL: 0s
```

```bash
docker compose -f docker-compose.yml -f nocache.yml up -d --no-deps --no-build api-gateway
# 측정 후 원래 설정으로 복구
docker compose -f docker-compose.yml up -d --no-deps --no-build api-gateway
```

## 주의

- 1)을 빼먹으면 이미지 빌드가 캐시된 옛 레이어(옛 jar)를 그대로 쓴다. 컨테이너 안 jar 날짜로 확인한다: `docker exec logistics-msa-api-gateway-1 ls -l /app/app.jar`
- 재기동 직후에는 JIT 워밍업 때문에 수치가 튀므로 15초 이상 워밍업 후 측정한다.
