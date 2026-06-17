package com.bluelight.backend.domain.user;

/**
 * LEW 본인 PayNow 수취 계정 유형 (LEW 초대/가입 + PayNow 수집).
 * <p>
 * LEW 가 플랫폼으로부터 정산/대금을 받는 <b>본인 수취 계좌</b>의 종류이다.
 * 신청자가 플랫폼에 결제하는 {@code system_settings} 의 PayNow(플랫폼 계좌)와는 무관하며,
 * per-LEW 개인 데이터로 {@code users} 테이블에 저장된다. (스펙 §1.4)
 * <p>
 * 둘 중 <b>하나만</b> 선택한다(D-PN1/D-PN2 — type+value 단일쌍).
 *
 * - COMPANY_UEN: 회사 UEN 기반 PayNow (10자, 예 {@code 201837490N})
 * - MOBILE: 휴대폰 번호 기반 PayNow (8자리, 예 {@code 97771983})
 */
public enum PaynowType {
    COMPANY_UEN,
    MOBILE
}
