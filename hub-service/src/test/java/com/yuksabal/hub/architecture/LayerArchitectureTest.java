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

// DDD 계층(presentation → application → domain ← infrastructure)의 의존 방향을 테스트로 강제한다 (#262)
// 안쪽 계층은 바깥 계층을 모른다
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

    // 서비스는 자기 입력(조건·명령)과 출력(결과)을 application/dto에 두고, 요청·응답 DTO 변환은 컨트롤러가 맡는다
    // 기존 위반을 동결해 두었다가 DTO 분리로 0개가 되어 일반 규칙으로 바꿨다 (#284)
    @ArchTest
    static final ArchRule application은_presentation에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..presentation..")
            .as("application must not depend on presentation");

    // ---- 애그리거트 경계 ----

    // 허브·재고·경로는 별도 애그리거트다. 재고·경로 엔티티는 허브를 객체가 아니라 ID로만 참조한다 (#268)
    @ArchTest
    static final ArchRule 재고와_경로_domain은_허브_domain을_모른다 = noClasses()
            .that().resideInAnyPackage("com.yuksabal.hub.inventory.domain..", "com.yuksabal.hub.route.domain..")
            .should().dependOnClassesThat().resideInAPackage("com.yuksabal.hub.hub.domain..")
            .as("inventory and route domains must not depend on hub domain");

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
