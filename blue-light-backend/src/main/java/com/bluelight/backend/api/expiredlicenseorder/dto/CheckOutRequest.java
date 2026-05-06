package com.bluelight.backend.api.expiredlicenseorder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckOutRequest {

    @NotNull(message = "visitReportFileSeq is required")
    private Long visitReportFileSeq;

    @Size(max = 2000, message = "managerNote must be 2000 chars or fewer")
    private String managerNote;
}
