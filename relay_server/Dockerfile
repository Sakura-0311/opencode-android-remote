# v4.2.0/O3: 多阶段构建——builder 阶段装依赖到 /install，final 阶段只拷
# /usr/local + server.py；构建层缓存与 pip 工具链不进最终镜像。
# 基础镜像保持 python:3.11-slim；构建上下文为 relay_server/ 目录。
FROM python:3.11-slim AS builder

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --prefix=/install -r /tmp/requirements.txt

FROM python:3.11-slim

WORKDIR /app

# 只拷依赖（/install -> /usr/local）+ server.py
COPY --from=builder /install /usr/local
COPY server.py .

EXPOSE 8765

CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8765"]
