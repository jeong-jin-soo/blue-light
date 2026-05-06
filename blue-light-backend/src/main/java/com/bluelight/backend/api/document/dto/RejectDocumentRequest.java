package com.bluelight.backend.api.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * LEW 반려 Request
 *
 * AC-S3 — rejectionReason min 10자, max 1000자. 누락 시 400 REJECTION_REASON_REQUIRED.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RejectDocumentRequest {

    @NotBlank(message = "rejectionReason is required")
    @Size(min = 10, max = 1000, message = "rejectionReason must be 10–1000 characters")
    private String rejectionReason;
}
