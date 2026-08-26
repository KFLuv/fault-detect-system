@echo off
chcp 65001 >nul
title 故障检测系统 - 启动器
echo ============================================
echo   故障检测系统（32 状态码 x 92 场景）
echo ============================================
cd /d %~dp0backend

REM 首次使用：自动创建虚拟环境并安装依赖
if not exist ..\.venv\Scripts\python.exe (
    echo [首次使用] 正在创建虚拟环境...
    python -m venv ..\.venv
    echo [首次使用] 正在安装依赖（fastapi/uvicorn/requests）...
    ..\.venv\Scripts\python.exe -m pip install -r requirements.txt
    if errorlevel 1 (
        echo 依赖安装失败，请检查网络后重试。
        pause
        exit /b 1
    )
)

echo 正在启动服务...
echo 浏览器访问： http://localhost:8000
REM 延迟 3 秒自动打开浏览器
PowerShell -NoProfile -Command "Start-Sleep -Seconds 3; Start-Process 'http://localhost:8000'" >nul 2>&1

..\.venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000
pause
