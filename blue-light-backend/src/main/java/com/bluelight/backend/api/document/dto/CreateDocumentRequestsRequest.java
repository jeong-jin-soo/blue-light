package com.bluelight.backend.api.document.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * LEW 배치 서류 요청 생성 Request
 *
 * AC-R1, R2, R3, R5 커버. 빈 배치는 400 ITEMS_EMPTY.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateDocumentRequestsRequest {

    @NotEmpty(message = "items must not be empty")
    @Size(max = 10, message = "at most 10 items per batch")
    @Valid
    private List<DocumentRequestItemRequest> items;
}
