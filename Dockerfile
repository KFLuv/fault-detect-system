# 故障检测系统 - Docker 镜像
# 后端 FastAPI + 前端静态资源一体打包，容器内监听 8000 端口
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    FAULT_DETECT_DATA_DIR=/app/data \
    TZ=Asia/Shanghai

WORKDIR /app

# 先复制依赖清单，利用 Docker 层缓存
COPY backend/requirements.txt /app/backend/requirements.txt
RUN pip install --no-cache-dir -r /app/backend/requirements.txt

# 复制后端与前端
COPY backend /app/backend
COPY frontend /app/frontend

WORKDIR /app/backend

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
