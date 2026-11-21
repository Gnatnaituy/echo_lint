# ---- 构建阶段 ----
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /app

# 先拷贝 pom 以利用依赖层缓存
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true

COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- 运行阶段 ----
# 滚动 tag（恒 multi-arch）：2026-11-20 上游重推 21.0.13.1_2-jre 后该具体 tag 一度只有
# amd64 变体，Apple Silicon（arm64）无法构建；21-jre 始终含 amd64+arm64。待上游推全
# 新的 _N rebuild 后可再锁回具体小版本 tag。
FROM eclipse-temurin:21-jre
WORKDIR /app

# ffmpeg / ffprobe：双声道（双轨）录音的声道探测与左右声道分离
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]