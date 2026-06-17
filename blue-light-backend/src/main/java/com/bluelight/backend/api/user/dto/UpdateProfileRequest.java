package com.bluelight.backend.api.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Update profile request DTO
 */
@Getter
@NoArgsConstructor
public class UpdateProfileRequest {

    @NotBlank(message = "First name is required")
    @Size(max = 50, message = "First name must be 50 characters or less")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 50, message = "Last name must be 50 characters or less")
    private String lastName;

    @Size(max = 20, message = "Phone number must be 20 characters or less")
    private String phone;

    @Size(max = 50, message = "Licence number must be 50 characters or less")
    private String lewLicenceNo;

    @Size(max = 20, message = "LEW grade must be 20 characters or less")
    private String lewGrade;

    /** LEW 본인 PayNow 유형 (COMPANY_UEN / MOBILE). LEW가 프로필에서 변경 시에만 전달. */
    @Size(max = 20, message = "PayNow type must be 20 characters or less")
    private String paynowType;

    /** LEW 본인 PayNow 값 (MOBILE 8자리 / COMPANY_UEN 10자). */
    @Size(max = 20, message = "PayNow value must be 20 characters or less")
    private String paynowValue;

    @Size(max = 100, message = "Company name must be 100 characters or less")
    private String companyName;

    @Size(max = 20, message = "UEN must be 20 characters or less")
    private String uen;

    @Size(max = 50, message = "Designation must be 50 characters or less")
    private String designation;

    @Size(max = 255, message = "Address must be 255 characters or less")
    private String correspondenceAddress;

    @Size(max = 10, message = "Postal code must be 10 characters or less")
    private String correspondencePostalCode;
}
