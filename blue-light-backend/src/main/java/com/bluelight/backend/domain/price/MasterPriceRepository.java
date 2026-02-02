package com.bluelight.backend.domain.price;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MasterPrice Entity Repository
 */
@Repository
public interface MasterPriceRepository extends JpaRepository<MasterPrice, Long> {

    /**
     * 활성화된 단가 목록 조회
     */
    List<MasterPrice> findByIsActiveTrueOrderByKvaMinAsc();

    /**
     * 특정 용량에 해당하는 단가 조회
     */
    @Query("SELECT mp FROM MasterPrice mp WHERE mp.isActive = true AND mp.kvaMin <= :kva AND mp.kvaMax >= :kva")
    Optional<MasterPrice> findByKva(@Param("kva") Integer kva);
}
