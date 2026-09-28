# 故障检测系统 - Java 版 Docker 镜像
# 构建策略（同时满足两种场景）：
#   1. 本地已打包 java-backend/target/fault-detect-system.jar 时，直接复用该 jar，构建秒级完成
#   2. 全新克隆 / mvn clean 之后 target 为空时，回退到容器内 Maven 多阶段构建，无需本机 Maven/Java
# 前置（可选，用于命中策略 1）：
#   mvn -f java-backend/pom.xml clean package -DskipTests
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY . .
RUN if [ -f target/fault-detect-system.jar ]; then \
        mkdir -p /out && cp target/fault-detect-system.jar /out/fault-detect-system.jar; \
    else \
        mvn -q -B package -DskipTests \
        && mkdir -p /out && cp target/fault-detect-system.jar /out/fault-detect-system.jar; \
    fi

# 运行阶段（Java 11 JRE）
FROM eclipse-temurin:11-jre
ENV FAULT_DETECT_DATA_DIR=/app/data \
    TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
WORKDIR /app
COPY --from=build /out/fault-detect-system.jar /app/fault-detect-system.jar
EXPOSE 8000
CMD ["java", "-jar", "/app/fault-detect-system.jar"]
