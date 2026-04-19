package com.bluelight.backend.api.application;

import com.bluelight.backend.api.application.dto.CreateApplicationRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.domain.application.ApplicantType;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.KvaStatus;
import com.bluelight.backend.domain.application.SldRequestRepository;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 PR#1 — {@link ApplicationService#createApplication} 의 kVA 분기 검증.
 *
 * <ul>
 *   <li>kvaUnknown=true → 서버가 selectedKva=45 로 강제, kvaStatus=UNKNOWN, kvaSource=null</li>
 *   <li>kvaUnknown=false/null (기존) → kvaStatus=CONFIRMED, kvaSource=USER_INPUT</li>
 *   <li>악의적: kvaUnknown=true 인데 selectedKva=500 전송 → 서버가 45 로 덮어쓰기</li>
 * </ul>
 */
class ApplicationServiceKvaTest {

    private ApplicationRepository applicationRepository;
    private SldRequestRepository sldRequestRepository;
    private MasterPriceRepository masterPriceRepository;
    private PaymentRepository paymentRepository;
    private UserRepository userRepository;
    private FileRepository fileRepository;
    private AuditLogService auditLogService;
    private ApplicationService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        sldRequestRepository = mock(SldRequestRepository.class);
        masterPriceRepository = mock(MasterPriceRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        userRepository = mock(UserRepository.class);
        fileRepository = mock(FileRepository.class);
        auditLogService = mock(AuditLogService.class);
        service = new ApplicationService(
                applicationRepository, sldRequestRepository, masterPriceRepository,
                paymentRepository, userRepository, fileRepository, auditLogService);
    }

    private CreateApplicationRequest baseReq(Integer kva, Boolean unknown) {
        CreateApplicationRequest r = new CreateApplicationRequest();
        r.setAddress("1 Blk Test");
        r.setPostalCode("560001");
        r.setBuildingType("HDB_FLAT");
        r.setSelectedKva(kva);
        r.setApplicantType(ApplicantType.INDIVIDUAL);
        r.setKvaUnknown(unknown);
        return r;
    }

    private void stubCommon(Integer expectedLookupKva) {
        User user = mock(User.class);
        when(user.getUserSeq()).thenReturn(77L);
        when(userRepository.findById(77L)).thenReturn(Optional.of(user));
        when(userRepository.findByRoleAndApprovedStatus(any(), any())).thenReturn(List.of());

        MasterPrice mp = mock(MasterPrice.class);
        when(mp.getPrice()).thenReturn(new BigDecimal("350.00"));
        when(mp.getRenewalPrice()).thenReturn(new BigDecimal("250.00"));
        when(mp.getSldPrice()).thenReturn(new BigDecimal("0.00"));
        when(masterPriceRepository.findByKva(expectedLookupKva)).thenReturn(Optional.of(mp));

        // save returns the same entity
        when(applicationRepository.save(any(Application.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void kvaUnknown_true면_서버가_selectedKva_45로_강제하고_UNKNOWN_저장() {
        // 클라이언트가 200 을 보내도 서버가 45 로 덮어쓴다 (security §6).
        stubCommon(45);
        CreateApplicationRequest req = baseReq(200, Boolean.TRUE);

        service.createApplication(77L, req);

        ArgumentCaptor<Application> cap = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository).save(cap.capture());
        Application saved = cap.getValue();
        assertThat(saved.getSelectedKva()).isEqualTo(45);
        assertThat(saved.getKvaStatus()).isEqualTo(KvaStatus.UNKNOWN);
        assertThat(saved.getKvaSource()).isNull();
    }

    @Test
    void kvaUnknown_false면_CONFIRMED_USER_INPUT으로_저장() {
        stubCommon(100);
        CreateApplicationRequest req = baseReq(100, Boolean.FALSE);

        service.createApplication(77L, req);

        ArgumentCaptor<Application> cap = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository).save(cap.capture());
        Application saved = cap.getValue();
        assertThat(saved.getSelectedKva()).isEqualTo(100);
        assertThat(saved.getKvaStatus()).isEqualTo(KvaStatus.CONFIRMED);
        assertThat(saved.getKvaSource())
                .isEqualTo(com.bluelight.backend.domain.application.KvaSource.USER_INPUT);
    }

    @Test
    void kvaUnknown_누락_null_이면_CONFIRMED_USER_INPUT_하위호환() {
        stubCommon(45);
        CreateApplicationRequest req = baseReq(45, null);

        service.createApplication(77L, req);

        ArgumentCaptor<Application> cap = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository).save(cap.capture());
        Application saved = cap.getValue();
        assertThat(saved.getKvaStatus()).isEqualTo(KvaStatus.CONFIRMED);
        assertThat(saved.getKvaSource())
                .isEqualTo(com.bluelight.backend.domain.application.KvaSource.USER_INPUT);
    }
}
