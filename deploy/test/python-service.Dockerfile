# 通用 Python 薄层服务镜像；build-arg SERVICE=retrieval-proxy|decide|gateway，MODULE=包名
FROM python:3.11-slim
ARG SERVICE
ARG MODULE
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ENV PIP_INDEX_URL=${PIP_INDEX_URL} PYTHONUNBUFFERED=1 APP_MODULE=${MODULE}
WORKDIR /app
COPY services/${SERVICE}/pyproject.toml ./
COPY services/${SERVICE}/src ./src
RUN pip install --no-cache-dir . "uvicorn>=0.29"
EXPOSE 8000
CMD ["sh", "-c", "exec python -m uvicorn ${APP_MODULE} --host 0.0.0.0 --port 8000"]
