package com.bluelight.backend.api.loa;

import com.bluelight.backend.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 전자서명 기능 비활성화 게이트 (보안 이슈 — 2026-06-13).
 * <p>
 * LOA 인앱 전자서명 수집(신청자 직접 서명 / Manager 대리 업로드)과 프로필 저장 서명을
 * 전면 차단한다. LOA 문서 자체의 생성·업로드·다운로드는 영향받지 않는다.
 * <p>
 * 기능 복구 시 각 컨트롤러 진입부의 {@link #exception()} 호출만 제거하면 된다.
 */
public final class SignatureDisabled {

    public static final String CODE = "SIGNATURE_DISABLED";
    public static final String MESSAGE =
            "Digital signature has been disabled for security reasons.";

    private SignatureDisabled() {
    }

    public static BusinessException exception() {
        return new BusinessException(MESSAGE, HttpStatus.FORBIDDEN, CODE);
    }
}
