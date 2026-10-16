package com.yuksabal.company.company.client.dto.response;

import java.util.UUID;

// hub 내부 API #7 응답. 존재 확인만 하므로 hubId만 받는다 (hub가 필드를 늘려도 나머지는 무시된다)
public record HubResponse(
        UUID hubId
) {
}
