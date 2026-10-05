# v4.2.0/O3: 多阶段构建——builder 阶段装依赖到 /install，final 阶段只拷
# /usr/local + server.py；构建层缓存与 pip 工具链不进最终镜像。
# 基础镜像保持 python:3.11-slim；构建上下文为 relay_server/ 目录。
FROM python:3.11-slim AS builder

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --prefix=/install -r /tmp/requirements.txt

FROM python:3.11-slim

WORKDIR /app

# 只拷依赖（/install -> /usr/local）+ server.py + crash_report.py
# （server.py 顶层 import crash_report，缺失则镜像无法启动）
COPY --from=builder /install /usr/local
COPY server.py crash_report.py ./

# 非 root 运行：建用户并把 /app（含 crash_reports）与家目录交给他
RUN useradd -m -u 1000 appuser \
    && mkdir -p /app/data /home/appuser/.config \
    && chown -R appuser:appuser /app /home/appuser
USER appuser

EXPOSE 8765

CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8765"]
