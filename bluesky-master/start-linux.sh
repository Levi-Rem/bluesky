#!/usr/bin/env bash
# =============================================================================
# BlueSky 训练平台 · Linux 一键启动/停止脚本
#
#   ./start-linux.sh                 # 启动 MySQL + Adapter + 平台（幂等，已在跑则跳过）
#   ./start-linux.sh --build         # 强制重新构建前端 + 打包 jar
#   ./start-linux.sh --no-build      # 跳过构建（最快启动）
#   ./start-linux.sh --stop          # 停止 Adapter + 平台
#   ./start-linux.sh --no-provision  # 跳过 MySQL/venv 自举（仅启动既有服务）
#
# 环境变量（可选；等价于 Windows 版 .env.local / 环境变量）：
#   BS_PLATFORM_PORT / BS_MYSQL_HOST / BS_MYSQL_PORT /
#   BS_MYSQL_USERNAME / BS_MYSQL_PASSWORD / BS_ADAPTER_CONTROL_ENDPOINT / BS_ADAPTER_STATE_ENDPOINT
# 也支持在脚本同目录放 .env.local（每行 KEY=VALUE，# 注释）。
# 服务器默认：8080 被 headscale 占用 → 平台默认起在 8090。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"            # bluesky-master/
PLATFORM="$ROOT/training-platform"
RUN_DIR="$PLATFORM/.run"
JAR="$PLATFORM/target/training-platform.jar"
ADAPTER_LOG="$RUN_DIR/adapter.out.log"
ADAPTER_ERR="$RUN_DIR/adapter.err.log"
PLATFORM_LOG="$RUN_DIR/platform.out.log"
PLATFORM_ERR="$RUN_DIR/platform.err.log"
PID_ADAPTER="$RUN_DIR/adapter.pid"
PID_PLATFORM="$RUN_DIR/platform.pid"

PLATFORM_PORT="${BS_PLATFORM_PORT:-8090}"
MYSQL_HOST="${BS_MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${BS_MYSQL_PORT:-3306}"
CONTROL_ENDPOINT="${BS_ADAPTER_CONTROL_ENDPOINT:-tcp://127.0.0.1:5555}"
STATE_ENDPOINT="${BS_ADAPTER_STATE_ENDPOINT:-tcp://127.0.0.1:5556}"
MYSQL_DB="${BS_MYSQL_DATABASE:-bluesky_training}"
VENV="${BS_VENV:-$HOME/bs-venv}"
PIP_INDEX="${PIP_INDEX:-https://pypi.tuna.tsinghua.edu.cn/simple}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"

BUILD_MODE="auto"     # auto | always | never
STOP_ONLY=false
PROVISION=true

# ---- 命令行开关 ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build)       BUILD_MODE="always"; shift ;;
    --no-build)    BUILD_MODE="never"; shift ;;
    --stop)        STOP_ONLY=true; shift ;;
    --no-provision) PROVISION=false; shift ;;
    -h|--help)     sed -n '1,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$RUN_DIR"

# 加载 .env.local（若存在）
_load_env() {
  [[ -f "$ROOT/.env.local" ]] || return 0
  while IFS='=' read -r k v; do
    k="${k//[[:space:]]/}"; [[ -z "$k" || "$k" =~ ^# ]] && continue
    v="${v%\"}"; v="${v#\"}"
    export "$k=$v"
  done < "$ROOT/.env.local"
}
_load_env

color() { printf '\033[%sm%s\033[0m\n' "$1" "$2"; }
info() { color '36' "$*"; }
ok()   { color '32' "OK   $*"; }
warn() { color '33' "WARN $*"; }
die()  { color '31' "FAIL $*" >&2; exit 1; }

_pid_alive() { [[ -f "$1" ]] && kill -0 "$(cat "$1")" 2>/dev/null; }
_port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&- 3<&-; return 0; } || return 1; }

stop_all() {
  info "--stop：停止平台 + Adapter"
  # 1) 按 pidfile 停（真实进程 PID）；2) 兜底按命令行模式停，避免孤儿
  for pidf in "$PID_PLATFORM" "$PID_ADAPTER"; do
    [[ -f "$pidf" ]] && kill "$(cat "$pidf")" 2>/dev/null && : || true
  done
  pkill -f 'bluesky.plugins.training_adapter.runner' 2>/dev/null || true
  pkill -f 'training-platform\.jar' 2>/dev/null || true
  sleep 2
  rm -f "$PID_PLATFORM" "$PID_ADAPTER"
  _port_busy 8090 && warn "8090 仍被占用（可能非本脚本进程）" || ok "平台已停止"
  _port_busy 5555 && warn "5555 仍被占用" || ok "Adapter 已停止"
}
[[ "$STOP_ONLY" == true ]] && { stop_all; exit 0; }

# ---- 前置检查 ----
for cmd in java python3 node npm; do
  command -v "$cmd" >/dev/null 2>&1 || die "缺少命令: $cmd（请先安装）"
done
command -v mvn >/dev/null 2>&1 || warn "未找到 mvn（将跳过 Java 构建，仅当已有 jar 时可用）"

# =============================================================================
# 1) MySQL：起服务 + 建库建用户（幂等）
# =============================================================================
if [[ "$PROVISION" == true ]]; then
  info "[1/4] MySQL（$MYSQL_HOST:$MYSQL_PORT）"
  if _port_busy 3306 && mysqladmin ping -h"$MYSQL_HOST" -P"$MYSQL_PORT" --silent 2>/dev/null; then
    ok "MySQL 已在运行"
  else
    if command -v systemctl >/dev/null && systemctl is-active mysql >/dev/null 2>&1; then
      ok "MySQL 服务已激活"
    else
      sudo systemctl start mysql 2>/dev/null || warn "无法自动启动 mysql，请手动启动后重试"
    fi
    sleep 2
  fi

  # 未提供用户名/密码时，用 root 自举默认库/账号（相当于 Windows 版 -DemoDatabase）。
  # 自举为 best-effort：受限环境（无 sudo / no_new_privs）下跳过并继续，要求库已预先建好。
  if [[ -z "${BS_MYSQL_USERNAME:-}" && -z "${BS_MYSQL_PASSWORD:-}" ]]; then
    info "  未设置 BS_MYSQL_USERNAME/PASSWORD，使用默认演示账号 bluesky/bluesky"
    if sudo -n true 2>/dev/null; then
      sudo mysql -e "CREATE DATABASE IF NOT EXISTS \`$MYSQL_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        CREATE USER IF NOT EXISTS 'bluesky'@'localhost' IDENTIFIED BY 'bluesky';
        GRANT ALL PRIVILEGES ON \`$MYSQL_DB\`.* TO 'bluesky'@'localhost'; FLUSH PRIVILEGES;" \
        && ok "数据库 $MYSQL_DB 与账号就绪" \
        || warn "MySQL 库/账号自举失败（由平台启动时 Flyway 兜底；若库不存在请先手工建库）"
    else
      warn "无 sudo 权限，跳过自动建库（假定库 $MYSQL_DB 已存在）"
    fi
  else
    info "  使用外部数据库账号 ${BS_MYSQL_USERNAME}"
  fi
fi

# =============================================================================
# 2) Python venv（缺失则创建并装依赖；Adapter 以 PYTHONPATH 直跑仓库源码）
# =============================================================================
info "[2/4] Python Adapter 环境"
if [[ "$PROVISION" == true && ! -x "$VENV/bin/python" ]]; then
  info "  创建 venv: $VENV（首次较慢）"
  python3 -m venv "$VENV"
  "$VENV/bin/python" -m pip install -q -U pip -i "$PIP_INDEX"
  "$VENV/bin/python" -m pip install -q \
    "numpy<2.4" scipy matplotlib pandas msgpack zmq bluesky-navdata openap -i "$PIP_INDEX"
fi
[[ -x "$VENV/bin/python" ]] || die "venv 不可用: $VENV（可设置 BS_VENV 指向已有 venv）"
ok "venv 就绪: $VENV"

# =============================================================================
# 3) 构建（前端 + jar）—— 仅在需要时
# =============================================================================
need_build=false
if [[ "$BUILD_MODE" == "always" ]]; then need_build=true
elif [[ "$BUILD_MODE" == "auto" && ! -f "$JAR" ]]; then need_build=true
fi

if [[ "$need_build" == true ]]; then
  info "[3/4] 构建前后端"
  command -v mvn >/dev/null 2>&1 || die "需要 jar 但未安装 mvn"
  if [[ ! -d "$PLATFORM/frontend/node_modules" ]]; then
    (cd "$PLATFORM/frontend" && npm install --registry="$NPM_REGISTRY" --no-audit --no-fund) \
      || die "npm 依赖安装失败"
  fi
  (cd "$PLATFORM/frontend" && npm run build) || die "前端构建失败"
  (cd "$PLATFORM" && mvn -B -q -DskipTests package) || die "Maven 打包失败"
  ok "构建完成: $JAR"
else
  [[ -f "$JAR" ]] || die "jar 不存在且未构建: $JAR（用 --build 强制构建）"
  info "[3/4] 使用既有构建产物: $JAR"
fi

# =============================================================================
# 4) 启动 Adapter + 平台（幂等：已在跑则跳过）
# =============================================================================
info "[4/4] 启动服务"
if _port_busy "${CONTROL_ENDPOINT##*:}"; then
  _pid_alive "$PID_ADAPTER" && ok "Adapter 已在运行（pid $(cat "$PID_ADAPTER")）" || warn "端口 ${CONTROL_ENDPOINT##*:} 被占用但非本脚本托管"
else
  # 用 exec 让 python 顶替后台子 shell 的 PID，pidfile 存真实进程 PID
  ( cd "$ROOT" && exec env PYTHONPATH="$ROOT" "$VENV/bin/python" -m bluesky.plugins.training_adapter.runner \
      --control-endpoint "$CONTROL_ENDPOINT" --state-endpoint "$STATE_ENDPOINT" ) \
      >"$ADAPTER_LOG" 2>"$ADAPTER_ERR" &
  echo $! > "$PID_ADAPTER"
  sleep 5
  _pid_alive "$PID_ADAPTER" || die "Adapter 启动失败（见 $ADAPTER_ERR）"
  ok "Adapter 已启动（pid $(cat "$PID_ADAPTER")，端口 ${CONTROL_ENDPOINT##*:}/${STATE_ENDPOINT##*:}）"
fi

if _port_busy "$PLATFORM_PORT"; then
  _pid_alive "$PID_PLATFORM" && ok "平台已在运行（pid $(cat "$PID_PLATFORM")，端口 $PLATFORM_PORT）" \
    || warn "端口 $PLATFORM_PORT 被占用但非本脚本托管"
else
  ( cd "$PLATFORM" && exec env BS_PLATFORM_PORT="$PLATFORM_PORT" java -jar "$JAR" ) \
      >"$PLATFORM_LOG" 2>"$PLATFORM_ERR" &
  echo $! > "$PID_PLATFORM"
  ok "平台启动中（端口 $PLATFORM_PORT）…"
fi

# ---- 健康检查 ----
info "  健康检查: http://127.0.0.1:$PLATFORM_PORT/actuator/health"
for _ in $(seq 1 30); do
  if curl -sf -m 2 "http://127.0.0.1:$PLATFORM_PORT/actuator/health" >/dev/null 2>&1; then
    ok "平台健康检查通过"
    color '32' "════════════════════════════════════════════════════"
    color '32' "  工作台已就绪:  http://127.0.0.1:$PLATFORM_PORT/"
    color '32' "  远程访问:      ssh -N -L $PLATFORM_PORT:127.0.0.1:$PLATFORM_PORT ubuntu@<服务器IP>"
    color '32' "  日志:          $RUN_DIR/*.log  停止: ./start-linux.sh --stop"
    color '32' "════════════════════════════════════════════════════"
    exit 0
  fi
  sleep 2
done
die "平台未在 60 秒内就绪（见 $PLATFORM_LOG / $PLATFORM_ERR）"
