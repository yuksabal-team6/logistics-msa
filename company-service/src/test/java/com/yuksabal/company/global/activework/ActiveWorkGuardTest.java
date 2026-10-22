package com.yuksabal.company.global.activework;

import com.yuksabal.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;
import static com.yuksabal.company.global.exception.CompanyErrorCode.HAS_ACTIVE_WORK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("NonAsciiCharacters")
class ActiveWorkGuardTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void 남은_업무가_없으면_통과한다() {
        ActiveWorkGuard guard = new ActiveWorkGuard(List.of(new FakeChecker("inventory"), new FakeChecker("order")));

        assertThatCode(() -> guard.validateDeletable(ActiveWorkTarget.PRODUCT, ID)).doesNotThrowAnyException();
    }

    @Test
    void 남은_업무가_있으면_409이고_details에_종류와_이유가_담긴다() {
        ActiveWorkGuard guard = new ActiveWorkGuard(List.of(
                new FakeChecker("inventory", "허브에 재고가 남아 있습니다."),
                new FakeChecker("order")));

        assertThatThrownBy(() -> guard.validateDeletable(ActiveWorkTarget.PRODUCT, ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(HAS_ACTIVE_WORK);
                    assertThat(e.getMessage()).isEqualTo("재고나 진행 중인 주문이 있어 상품을 삭제할 수 없습니다.");
                    assertThat(e.getDetails()).extracting("field").containsExactly("inventory");
                });
    }

    @Test
    void 여러_서비스에_업무가_남아_있으면_모두_알려준다() {
        ActiveWorkGuard guard = new ActiveWorkGuard(List.of(
                new FakeChecker("inventory", "허브에 재고가 남아 있습니다."),
                new FakeChecker("order", "진행 중인 주문이 있습니다.")));

        assertThatThrownBy(() -> guard.validateDeletable(ActiveWorkTarget.COMPANY, ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("재고나 진행 중인 주문이 있어 업체를 삭제할 수 없습니다.");
                    assertThat(e.getDetails()).extracting("field").containsExactly("inventory", "order");
                });
    }

    @Test
    void 확인하지_못하면_502로_막는다() {
        ActiveWorkGuard guard = new ActiveWorkGuard(List.of(
                new FakeChecker("inventory"),
                new FailingChecker("order", new IllegalStateException("타임아웃"))));

        assertThatThrownBy(() -> guard.validateDeletable(ActiveWorkTarget.PRODUCT, ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(DEPENDENCY_FAILURE);
                    assertThat(e.getMessage()).isEqualTo("남은 업무를 확인하지 못해 상품을 삭제할 수 없습니다.");
                });
    }

    @Test
    void 구현체가_던진_BusinessException은_그대로_전달한다() {
        BusinessException original = new BusinessException(DEPENDENCY_FAILURE);
        ActiveWorkGuard guard = new ActiveWorkGuard(List.of(new FailingChecker("inventory", original)));

        assertThatThrownBy(() -> guard.validateDeletable(ActiveWorkTarget.COMPANY, ID)).isSameAs(original);
    }

    @Test
    void 허브_변경_전_확인은_업체_기준이고_메시지가_허브_변경으로_나온다() {
        RecordingChecker checker = new RecordingChecker("inventory", "허브에 재고가 남아 있습니다.");
        ActiveWorkGuard guard = new ActiveWorkGuard(List.of(checker));

        assertThatThrownBy(() -> guard.validateHubChangeable(ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(HAS_ACTIVE_WORK);
                    assertThat(e.getMessage()).isEqualTo("재고나 진행 중인 주문이 있어 업체의 관리 허브를 바꿀 수 없습니다.");
                    assertThat(e.getDetails()).extracting("field").containsExactly("inventory");
                });
        assertThat(checker.target).isEqualTo(ActiveWorkTarget.COMPANY);
    }

    @Test
    void 허브_변경_전_확인을_하지_못하면_502() {
        ActiveWorkGuard guard = new ActiveWorkGuard(List.of(new FailingChecker("order", new IllegalStateException("타임아웃"))));

        assertThatThrownBy(() -> guard.validateHubChangeable(ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(DEPENDENCY_FAILURE);
                    assertThat(e.getMessage()).isEqualTo("남은 업무를 확인하지 못해 업체의 관리 허브를 바꿀 수 없습니다.");
                });
    }

    // 테스트용 구현체. record의 type() 접근자가 인터페이스의 type()을 구현한다
    private record FakeChecker(String type, List<String> blockers) implements ActiveWorkChecker {
        FakeChecker(String type, String... blockers) {
            this(type, List.of(blockers));
        }

        @Override
        public List<String> findBlockers(ActiveWorkTarget target, UUID id) {
            return blockers;
        }
    }

    // 어떤 대상으로 확인을 요청받았는지 기록한다
    private static class RecordingChecker implements ActiveWorkChecker {
        private final String type;
        private final List<String> blockers;
        private ActiveWorkTarget target;

        RecordingChecker(String type, String... blockers) {
            this.type = type;
            this.blockers = List.of(blockers);
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public List<String> findBlockers(ActiveWorkTarget target, UUID id) {
            this.target = target;
            return blockers;
        }
    }

    private record FailingChecker(String type, RuntimeException error) implements ActiveWorkChecker {
        @Override
        public List<String> findBlockers(ActiveWorkTarget target, UUID id) {
            throw error;
        }
    }
}
