package com.yuksabal.hub.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

// DDD 계층(presentation → application → domain ← infrastructure)의 의존 방향을 테스트로 강제한다 (#262)
// 안쪽 계층은 바깥 계층을 모른다. 규칙 설명 문자열(as)은 동결 기록을 식별하는 키라 바꾸지 않는다
@AnalyzeClasses(packages = "com.yuksabal.hub", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerArchitectureTest {

    // ---- 계층 의존 규칙 ----

    @ArchTest
    static final ArchRule domain은_바깥_계층에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..presentation..", "..infrastructure..")
            .as("domain must not depend on application, presentation, infrastructure");

    // DIP: 다른 서비스 호출은 application의 ProductReader·CompanyReader를 통해서만
    @ArchTest
    static final ArchRule application은_infrastructure에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .as("application must not depend on infrastructure");

    // presentation(들어오는 쪽)과 infrastructure(나가는 쪽)는 둘 다 바깥 어댑터라 서로 모른다
    @ArchTest
    static final ArchRule presentation은_infrastructure에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..presentation..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .as("presentation must not depend on infrastructure");

    // 외부 호출 코드(Feign 어댑터 등)가 우리 API의 요청·응답 DTO에 묶이지 않게 한다
    @ArchTest
    static final ArchRule infrastructure는_presentation에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("..presentation..")
            .as("infrastructure must not depend on presentation");

    // 서비스가 presentation의 요청·응답 DTO를 받고 돌려주는 기존 의존은 동결한다 (archunit_store에 기록)
    // 새 위반은 실패하고, 서비스별 DTO 분리 후속 이슈에서 기록이 줄어든다. 완료 기준은 기록의 위반 0개
    @ArchTest
    static final ArchRule application은_presentation에_의존하지_않는다 = freeze(noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..presentation..")
            .as("application must not depend on presentation"));

    // ---- 위치 규칙 ----

    @ArchTest
    static final ArchRule 컨트롤러는_presentation에_있다 = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage("..presentation..")
            .as("rest controllers reside in presentation");

    @ArchTest
    static final ArchRule 서비스는_application에_있다 = classes()
            .that().areAnnotatedWith(Service.class)
            .should().resideInAPackage("..application..")
            .as("services reside in application");

    @ArchTest
    static final ArchRule 엔티티는_domain에_있다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..domain..")
            .as("entities reside in domain");

    @ArchTest
    static final ArchRule Feign_클라이언트는_infrastructure에_있다 = classes()
            .that().areAnnotatedWith(FeignClient.class)
            .should().resideInAPackage("..infrastructure..")
            .as("feign clients reside in infrastructure");
}
