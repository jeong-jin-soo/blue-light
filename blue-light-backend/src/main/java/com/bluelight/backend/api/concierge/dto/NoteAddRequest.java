package com.bluelight.backend.api.concierge.dto;

import com.bluelight.backend.domain.concierge.NoteChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ConciergeNote 추가 요청 DTO (★ Phase 1 PR#4 Stage A).
 */
@Getter
@Setter
@NoArgsConstructor
public class NoteAddRequest {

    @NotNull
    private NoteChannel channel;

    @NotBlank
    @Size(max = 2000)
    private String content;
}
