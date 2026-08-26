# 故障检测系统 - Java 版 Docker 镜像
# 阶段一：Maven 构建 jar
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# 阶段二：运行（Java 11 JRE）
FROM eclipse-temurin:11-jre
ENV FAULT_DETECT_DATA_DIR=/app/data \
    TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
WORKDIR /app
COPY --from=build /build/target/fault-detect-system.jar /app/fault-detect-system.jar
EXPOSE 8000
CMD ["java", "-jar", "/app/fault-detect-system.jar"]
