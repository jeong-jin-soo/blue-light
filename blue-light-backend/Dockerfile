# ============================================
# Stage 1: Build
# ============================================
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

# Gradle wrapper + 설정 파일 복사 (캐시 활용)
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./

# 의존성 먼저 다운로드 (소스 변경 시 캐시 재사용)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# 소스 복사 및 빌드
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# ============================================
# Stage 2: Runtime
# ============================================
FROM eclipse-temurin:17-jre

WORKDIR /app

# 업로드 디렉토리 생성
RUN mkdir -p /app/uploads

# JAR 복사
COPY --from=builder /app/build/libs/*.jar app.jar

# 포트 노출
EXPOSE 8090

# 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
