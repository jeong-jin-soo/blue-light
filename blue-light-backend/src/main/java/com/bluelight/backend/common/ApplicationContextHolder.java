package com.bluelight.backend.common;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Spring `ApplicationContext`를 static 필드로 보관하여 JPA `AttributeConverter`
 * 등 Spring 컨테이너가 직접 인스턴스화할 수 없는 지점에서 빈을 조회하기 위한
 * 얇은 홀더.
 *
 * 일반 비즈니스 코드에서는 사용하지 말 것 — 대신 생성자 주입을 사용한다.
 */
@Component
public class ApplicationContextHolder implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        ApplicationContextHolder.context = applicationContext;
    }

    /**
     * 지정한 타입의 빈을 조회한다.
     * 컨텍스트가 아직 초기화되지 않았다면 null 을 반환한다.
     */
    public static <T> T getBean(Class<T> type) {
        if (context == null) {
            return null;
        }
        return context.getBean(type);
    }

    /**
     * 테스트 편의용 — 테스트에서 명시적으로 컨텍스트를 지정하거나 초기화할 때 사용.
     */
    public static void setContext(ApplicationContext applicationContext) {
        ApplicationContextHolder.context = applicationContext;
    }
}
