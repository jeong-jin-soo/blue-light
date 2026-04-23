package com.bluelight.backend.domain.docnumber;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * {@link DocumentNumberSequence} 복합 PK 식별자.
 * JPA {@code @IdClass} 에서 요구하는 serializable + equals/hashCode 구현.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class DocumentNumberSequenceId implements Serializable {

    private String docTypeCode;
    private LocalDate issueDate;
}
