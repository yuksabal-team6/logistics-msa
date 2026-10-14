package com.yuksabal.order.sample.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.order.sample.dto.request.SampleCreateRequest;
import com.yuksabal.order.sample.dto.response.SampleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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
            // 404는 RESOURCE_NOT_FOUND 하나만 쓰고, 무엇이 없는지는 message·field로 알려준다
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않거나 삭제된 샘플입니다.", "sampleId");
        }
        return sample;
    }

    // 실제 서비스: Page<Order> page = orderRepository.findAll(pageable); 처럼 Repository에 pageable을 그대로 넘긴다
    public Page<SampleResponse> getList(Pageable pageable) {
        List<SampleResponse> all = new ArrayList<>(store.values());
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new PageImpl<>(all.subList(from, to), pageable, all.size());
    }
}
