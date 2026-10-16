package com.yuksabal.user.user.client.dto;

import java.util.UUID;

// #2 업체 조회 응답. 존재 확인에만 쓰므로 필요한 필드만 받는다 (나머지 필드는 무시된다)
public record CompanyResponse(
        UUID companyId
) {
}
