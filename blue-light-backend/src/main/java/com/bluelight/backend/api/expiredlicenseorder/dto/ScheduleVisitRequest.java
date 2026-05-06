package com.bluelight.backend.api.expiredlicenseorder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleVisitRequest {

    @NotNull(message = "visitScheduledAt is required")
    private LocalDateTime visitScheduledAt;

    @Size(max = 2000, message = "visitScheduleNote must be 2000 chars or fewer")
    private String visitScheduleNote;
}
