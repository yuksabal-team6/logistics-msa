// Gateway 인증 요청 부하 테스트 (k6) — 사용자 정보 조회 캐싱(#296) 적용 전후 비교용
//
// 측정 대상: GET /api/v1/auth/me
//   Gateway가 JWT 검증 → user-service에 역할·활성 상태 조회(캐시 대상) → user-service /me 로 라우팅
//   캐시 끄면 요청당 user-service 2회 호출, 켜면 TTL(5초) 안에서는 1회
//
// 실행 (설치 없이 Docker, 로컬 docker compose가 떠 있는 상태):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 \
//     -e USERNAME=<활성 회원 아이디> -e PASSWORD=<비밀번호> \
//     grafana/k6 run - < api-gateway/loadtest/auth-me.js
//
// 캐시 끄고 비교: api-gateway 컨테이너에 AUTH_USERSTATUSCACHE_TTL=0s 를 주고 (ttl 0이면 캐시 끔) 재기동한 뒤 같은 명령 실행
// 옵션: VUS(동시 사용자, 기본 50), DURATION(기본 30s)
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    auth_me: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 한 번 로그인해서 받은 Access Token을 모든 VU가 쓴다 (같은 회원 → 캐시 효과를 보는 조건)
export function setup() {
  if (!__ENV.USERNAME || !__ENV.PASSWORD) {
    fail('USERNAME, PASSWORD 환경 변수가 필요합니다');
  }
  const res = http.post(`${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: __ENV.USERNAME, password: __ENV.PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 200) {
    fail(`로그인 실패: ${res.status} ${res.body}`);
  }
  return { token: res.json('data.accessToken') };
}

export default function (data) {
  const res = http.get(`${BASE_URL}/api/v1/auth/me`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}
