package com.bluelight.backend.domain.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AOP 감사 로그 어노테이션
 * - 컨트롤러 메서드에 적용하면 AuditAspect가 자동으로 감사 로그를 기록
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    AuditAction action();
    AuditCategory category();
    String entityType() default "";
    String description() default "";
}
