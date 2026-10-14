package com.yuksabal.hub.hub.repository;

import com.yuksabal.hub.hub.entity.Hub;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface HubRepository extends JpaRepository<Hub, UUID> {

    // 허브명 또는 주소에 검색어가 포함된 허브 (삭제된 허브는 @SQLRestriction으로 제외)
    @Query("SELECT h FROM Hub h WHERE h.hubName LIKE %:keyword% OR h.address LIKE %:keyword%")
    Page<Hub> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
