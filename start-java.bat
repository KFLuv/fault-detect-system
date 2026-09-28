@echo off
chcp 65001 >nul
title 故障检测系统（Java 版）- 启动器
echo ============================================
echo   故障检测系统 Java 版（32 状态码 x 92 场景）
echo   端口 8000
echo   注意：如 Docker 容器正在占用 8000 端口，请先执行
echo         docker compose -f docker-compose-java.yml down
echo ============================================
cd /d %~dp0

set MVN=E:\apache-maven-3.9.16\bin\mvn.cmd
set JAR=java-backend\target\fault-detect-system.jar

REM 首次使用：自动用 Maven 打包 jar
if not exist "%JAR%" (
    echo [首次使用] 正在编译打包（首次需下载依赖，请稍候）...
    "%MVN%" -f java-backend\pom.xml clean package -DskipTests
    if errorlevel 1 (
        echo 打包失败，请检查 Maven 安装与网络后重试。
        pause
        exit /b 1
    )
)

echo 正在启动服务...
echo 浏览器访问： http://localhost:8000
REM 延迟 5 秒自动打开浏览器
PowerShell -NoProfile -Command "Start-Sleep -Seconds 5; Start-Process 'http://localhost:8000'" >nul 2>&1

java -jar "%JAR%"
pause
