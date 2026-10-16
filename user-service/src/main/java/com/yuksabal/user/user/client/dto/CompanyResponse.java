package com.yuksabal.user.user.client.dto;

import java.util.UUID;

// #2 업체 조회, #11 관리 업체 목록의 항목. 존재·관리 업체 확인에 ID만 쓰므로 필요한 필드만 받는다 (나머지 필드는 무시된다)
public record CompanyResponse(
        UUID companyId
) {
}
