package com.yuksabal.order.sample.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.order.global.exception.OrderErrorCode;
import com.yuksabal.order.sample.dto.request.SampleCreateRequest;
import com.yuksabal.order.sample.dto.response.SampleResponse;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 참고용: DB 대신 메모리에 저장한다. 실제 서비스는 Repository를 사용한다.
@Service
public class SampleService {

    private final Map<UUID, SampleResponse> store = new ConcurrentHashMap<>();

    public SampleResponse create(SampleCreateRequest request) {
        SampleResponse sample = new SampleResponse(UUID.randomUUID(), request.name(), request.quantity());
        store.put(sample.id(), sample);
        return sample;
    }

    public SampleResponse get(UUID sampleId) {
        SampleResponse sample = store.get(sampleId);
        if (sample == null) {
            // 도메인 에러: {서비스}ErrorCode를 던지면 GlobalExceptionHandler가 ApiResponse로 바꿔 응답
            throw new BusinessException(OrderErrorCode.SAMPLE_NOT_FOUND);
        }
        return sample;
    }
}
