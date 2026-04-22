package com.bluelight.backend.domain.cof;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Certificate of Fitness Repository.
 */
@Repository
public interface CertificateOfFitnessRepository extends JpaRepository<CertificateOfFitness, Long> {

    /**
     * 특정 Application에 대한 CoF 레코드 조회 (1:1 매핑, 없으면 empty).
     */
    Optional<CertificateOfFitness> findByApplication_ApplicationSeq(Long applicationSeq);

    /**
     * MSSL Account No의 HMAC 해시로 CoF 조회 (중복 검증용).
     *
     * <p>P1.B에서 "동일 MSSL이 이미 다른 신청에 기입되어 있는지" 검증 시 활용한다.
     * 현재는 선언만 — 실제 사용처는 P1.B 서비스 레이어에서 연결.</p>
     */
    Optional<CertificateOfFitness> findByMsslAccountNoHmac(String msslAccountNoHmac);
}
