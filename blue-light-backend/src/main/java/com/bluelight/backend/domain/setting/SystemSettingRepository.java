package com.bluelight.backend.domain.setting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * SystemSetting Repository
 */
@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
}
