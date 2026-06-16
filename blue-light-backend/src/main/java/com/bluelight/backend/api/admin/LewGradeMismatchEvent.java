package com.bluelight.backend.api.admin;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.user.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

/**
 * kVA 변경 결과, 현재 배정된 LEW 가 새 kVA 를 등급상 처리할 수 없게 됐을 때 발행되는 이벤트(#5).
 *
 * <p>정책: <b>차단/자동 배정해제 없이 경고+플래그</b>. 변경은 그대로 진행하되
 * {@link LewGradeMismatchNotificationListener} 가 ADMIN/SYSTEM_ADMIN 에게 "재배정 필요" 인앱 알림을
 * 보낸다. 영구 가시 플래그는 {@code AdminApplicationResponse.assignedLewGradeMismatch}(파생값)가 담당.</p>
 *
 * @param applicationSeq 대상 Application PK
 * @param assignedLewSeq 현재 배정 LEW user_seq
 * @param lewGradeName   배정 LEW 등급명 (예: GRADE_7)
 * @param lewMaxKva      배정 LEW 등급의 최대 처리 kVA
 * @param newKva         변경된(초과) kVA
 */
@Getter
@RequiredArgsConstructor
public class LewGradeMismatchEvent {
    private final Long applicationSeq;
    private final Long assignedLewSeq;
    private final String lewGradeName;
    private final int lewMaxKva;
    private final int newKva;

    /**
     * 현재 신청 상태에서 "배정 LEW 등급 초과"가 성립하면 이벤트를 생성한다.
     * 배정 LEW 가 없거나 등급/kVA 미설정이거나 처리 가능하면 empty.
     */
    public static Optional<LewGradeMismatchEvent> detect(Application application) {
        User lew = application.getAssignedLew();
        Integer kva = application.getSelectedKva();
        if (lew == null || lew.getLewGrade() == null || kva == null) {
            return Optional.empty();
        }
        if (lew.canHandleKva(kva)) {
            return Optional.empty();
        }
        return Optional.of(new LewGradeMismatchEvent(
                application.getApplicationSeq(),
                lew.getUserSeq(),
                lew.getLewGrade().name(),
                lew.getLewGrade().getMaxKva(),
                kva));
    }
}
