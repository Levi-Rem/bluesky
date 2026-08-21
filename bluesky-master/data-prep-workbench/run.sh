#!/usr/bin/env bash
# 飞行数据准备与分析工作台 —— 一键打包并运行
# 用法：./run.sh          （打包 + 启动，访问 http://127.0.0.1:8090）
#       ./run.sh --skip-build   （直接启动已构建的 jar）
set -euo pipefail
cd "$(dirname "$0")"

JAR="target/data-prep-workbench.jar"

if [[ "${1:-}" != "--skip-build" ]]; then
  echo "==> 打包后端（跳过测试）"
  mvn -q -DskipTests package
fi

if [[ ! -f "$JAR" ]]; then
  echo "未找到 $JAR，请先去掉 --skip-build 参数执行完整构建" >&2
  exit 1
fi

echo "==> 启动：http://127.0.0.1:8090  （H2 文件库 ./data/，Ctrl+C 停止）"
exec java -jar "$JAR"
