package com.bluelight.backend.api.notification.channel.whatsapp;

import com.bluelight.backend.domain.notification.whatsapp.WhatsappProvider;

import java.util.List;

/**
 * WhatsApp 메시지 발송 클라이언트 추상화 (PR-1A).
 *
 * <p>구현체는 외부 provider 호출만 담당 (Meta Graph API, BSP 등). outbox row 의 상태 갱신,
 * 옵트인 가드, 템플릿 lookup 등은 {@code WhatsappChannelAdapter} 가 책임진다.</p>
 *
 * <h2>구현체 선택</h2>
 * <ul>
 *   <li>{@code whatsapp.provider=mock} (기본) — {@link MockWhatsappClient}. 외부 호출 없이 stdout 로그.</li>
 *   <li>{@code whatsapp.provider=meta} — {@code MetaCloudWhatsappClient} (PR-1B 에서 추가).</li>
 * </ul>
 * 향후 BSP(Wati, 360dialog 등) 전환 시 enum 값 추가 + 구현체 교체만으로 가능.
 */
public interface WhatsappClient {

    /** 본 클라이언트가 통신하는 provider. */
    WhatsappProvider provider();

    /**
     * 사전 승인된 템플릿을 통해 WhatsApp 메시지를 발송한다.
     *
     * <p>Meta Cloud API 와 대부분의 BSP 는 "template 발송" 만 허용 (24시간 세션 윈도우 외에는).
     * 따라서 본 인터페이스는 template-only 단순화. 양방향 대화/inbound 처리는 Webhook 책임 (PR-1C).</p>
     *
     * @param request E.164 수신자 + 사전 승인된 템플릿명 + 변수 슬롯 + idempotency key
     * @return 외부 호출 결과 — {@code SendResult.queued(providerMessageId)} 또는 {@code SendResult.failure}
     */
    SendResult sendTemplate(SendTemplateRequest request);

    /** 발송 요청. */
    record SendTemplateRequest(
            /** E.164 형식 수신자 번호 (+65...). 호출 측이 검증된 번호만 전달해야 한다. */
            String toE164,
            /** Meta/BSP 측 사전 승인된 템플릿 이름 (예: payment_confirmed_applicant). */
            String providerTemplateName,
            /** 메시지 언어 (예: en, ko, zh-Hans). Meta 가 template-language 매칭에 사용. */
            String locale,
            /** {{1}}, {{2}}, ... 위치 변수 (Meta Cloud API 의 components.body.parameters 와 매핑). */
            List<String> variables,
            /** 멱등성 키 — provider 측 dedupe id (Meta 는 dedupe 안 함, BSP 는 일부 지원). */
            String idempotencyKey
    ) {
    }

    /**
     * 발송 결과.
     *
     * @param status              QUEUED 면 provider 에 enqueue 완료 (실제 발송은 webhook 에서 확인).
     *                            REJECTED 면 provider 가 즉시 거절 (영구 실패).
     *                            ERROR 면 네트워크/일시 오류 (재시도 가능).
     * @param providerMessageId   Meta wamid 또는 BSP 메시지 id. status=QUEUED 일 때만 의미 있음.
     * @param errorCode           provider 측 에러 코드 (예: Meta error.code).
     * @param errorMessage        사람이 읽을 에러 메시지.
     */
    record SendResult(ProviderStatus status, String providerMessageId, String errorCode, String errorMessage) {

        public boolean isSuccess() {
            return status == ProviderStatus.QUEUED;
        }

        public boolean isRetryable() {
            return status == ProviderStatus.ERROR;
        }

        public static SendResult queued(String providerMessageId) {
            return new SendResult(ProviderStatus.QUEUED, providerMessageId, null, null);
        }

        /** provider 가 즉시 영구 거절 (템플릿 미승인, 번호 무효 등). */
        public static SendResult rejected(String errorCode, String errorMessage) {
            return new SendResult(ProviderStatus.REJECTED, null, errorCode, errorMessage);
        }

        /** 네트워크/타임아웃 등 일시 실패 — 재시도 대상. */
        public static SendResult error(String errorCode, String errorMessage) {
            return new SendResult(ProviderStatus.ERROR, null, errorCode, errorMessage);
        }
    }

    /** provider 측 응답 상태. */
    enum ProviderStatus {
        /** provider 에 enqueue 완료 — webhook 이 SENT/DELIVERED/READ 갱신 예정. */
        QUEUED,
        /** provider 가 즉시 거절 — 영구 실패. */
        REJECTED,
        /** 네트워크/타임아웃 등 일시 실패 — 재시도 대상. */
        ERROR
    }
}
