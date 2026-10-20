package com.yuksabal.company.global.activework;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 남은 업무 확인 대상. 오류 메시지와 로그에 쓰는 이름을 함께 둔다
@Getter
@RequiredArgsConstructor
public enum ActiveWorkTarget {
    PRODUCT("상품을", "productId"),
    COMPANY("업체를", "companyId");

    private final String objectName;   // 메시지용: "... 상품을 삭제할 수 없습니다."
    private final String idName;       // 로그용: productId=...
}
