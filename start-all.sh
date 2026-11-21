#!/usr/bin/env bash
# box-im 一键启停脚本
# 用法:
#   ./start.sh           启动 im-platform + im-server
#   ./start.sh stop      停止两个服务
#   ./start.sh restart   重启
#   ./start.sh status    查看进程 + 端口
#   ./start.sh logs      跟踪两份日志
set -u

# ============================================================
# 1) 集中配置 —— 改这里就够了
# ============================================================
PROJECT_ROOT=/www/wwwroot/chat
JAVA_HOME=/www/server/java/jdk-17.0.8

# Spring profile
PROFILE=prod

# Redis（box-im 与 aagames 必须同一个实例同一个 DB）
REDIS_HOST=172.31.4.67
REDIS_PORT=6379
REDIS_PASSWORD=Qc.223311
REDIS_DATABASE=3

# 数据库
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DB=im_platform
MYSQL_USER=root
MYSQL_PASSWORD='e57c1ca810e4945f'

# aagames_api（box-im 出站调用）
AAGAMES_BASE_URL=https://api.7pgb99.com
AAGAMES_OUTBOUND_SECRET=boxim_outbound_dev_secret_2026
AAGAMES_MERCHANT_NO=10070
AAGAMES_REGISTER_DOMAIN=chat.7pgb88.com
# 同域名时留空；分了用户域名才填
AAGAMES_USER_BASE_URL=""

# box-im 入站验签（aagames → box-im，如红包推送）；须与 aagames BOXIM_INBOUND_SECRET 一致
BOXIM_INBOUND_SECRET=boxim_inbound_dev_secret_2026
# 红包机器人 im_user.id（与 aagames BOXIM_BOT_USER_ID / BoximBotUserSeeder 一致）
BOXIM_REDPACKET_BOT_USER_ID=45

# AI 客服（旧 jar 无 application-prod.ai-cs 时靠启动参数生效）
AI_CS_ENABLED=true
AI_CS_BOT_USER_ID=900001
AI_CS_BOT_USER_IDS=(900001 900002)
AI_CS_KB_ENABLED=true
AI_CS_INGEST_ENABLED=true
AI_CS_INGEST_BOT_USER_ID=900003
AI_CS_HANDOFF_ENABLED=true

# OpenAI（AI 客服）；若已在 application-local.yml 里写过 key 可留空
OPENAI_API_KEY=""

# IM 管理后台白名单（im_user.id，对应 ai-cs.knowledge-ingest-bot.admin-user-ids）
ADMIN_USER_IDS=(40 52 86 87 88)

# 服务端口（仅用于健康检查输出）
PLATFORM_PORT=8888
SERVER_PORT=8878

# ============================================================
# 2) 内部派生
# ============================================================
LOG_DIR=$PROJECT_ROOT/logs
PLATFORM_JAR=$PROJECT_ROOT/im-platform/target/im-platform.jar
SERVER_JAR=$PROJECT_ROOT/im-server/target/im-server.jar

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$LOG_DIR"
cd "$PROJECT_ROOT"

# im-platform 命令行参数（优先级最高，覆盖 yml）
PLATFORM_ARGS=(
  "--spring.profiles.active=$PROFILE"

  # MySQL
  "--spring.datasource.url=jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$MYSQL_DB?useSSL=false&useUnicode=true&characterEncoding=utf-8&allowPublicKeyRetrieval=true"
  "--spring.datasource.username=$MYSQL_USER"
  "--spring.datasource.password=$MYSQL_PASSWORD"

  # Redis
  "--spring.data.redis.host=$REDIS_HOST"
  "--spring.data.redis.port=$REDIS_PORT"
  "--spring.data.redis.password=$REDIS_PASSWORD"
  "--spring.data.redis.database=$REDIS_DATABASE"

  # aagames / boxim（优先级高于 jar 内 yml；旧包没打 prod 配置也能跑）
  "--aagames.base-url=$AAGAMES_BASE_URL"
  "--aagames.outbound-secret=$AAGAMES_OUTBOUND_SECRET"
  "--aagames.merchant-no=$AAGAMES_MERCHANT_NO"
  "--aagames.register-domain=$AAGAMES_REGISTER_DOMAIN"
  "--boxim.inbound-secret=$BOXIM_INBOUND_SECRET"
  "--boxim.redpacket-bot.user-id=$BOXIM_REDPACKET_BOT_USER_ID"

  # AI 客服
  "--ai-cs.enabled=$AI_CS_ENABLED"
  "--ai-cs.bot-user-id=$AI_CS_BOT_USER_ID"
  "--ai-cs.knowledge-base.enabled=$AI_CS_KB_ENABLED"
  "--ai-cs.knowledge-ingest-bot.enabled=$AI_CS_INGEST_ENABLED"
  "--ai-cs.knowledge-ingest-bot.user-id=$AI_CS_INGEST_BOT_USER_ID"
  "--ai-cs.handoff.enabled=$AI_CS_HANDOFF_ENABLED"
)

[ -n "$AAGAMES_USER_BASE_URL" ] && PLATFORM_ARGS+=("--aagames.user-base-url=$AAGAMES_USER_BASE_URL")
[ -n "$OPENAI_API_KEY" ]        && PLATFORM_ARGS+=("--openai.api-key=$OPENAI_API_KEY")

# AI 机器人集合
idx=0
for id in "${AI_CS_BOT_USER_IDS[@]}"; do
  PLATFORM_ARGS+=("--ai-cs.bot-user-ids[$idx]=$id")
  idx=$((idx + 1))
done

# 管理后台白名单（必须加在 im-platform，不是 im-server）
idx=0
for id in "${ADMIN_USER_IDS[@]}"; do
  PLATFORM_ARGS+=("--ai-cs.knowledge-ingest-bot.admin-user-ids[$idx]=$id")
  idx=$((idx + 1))
done

# im-server 命令行参数
SERVER_ARGS=(
  "--spring.profiles.active=$PROFILE"
  "--spring.data.redis.host=$REDIS_HOST"
  "--spring.data.redis.port=$REDIS_PORT"
  "--spring.data.redis.password=$REDIS_PASSWORD"
  "--spring.data.redis.database=$REDIS_DATABASE"
)

# ============================================================
# 3) 子命令实现
# ============================================================
log() { echo -e "\033[1;32m[start.sh]\033[0m $*"; }
err() { echo -e "\033[1;31m[start.sh]\033[0m $*" >&2; }

kill_one() {
  local key=$1
  local pids
  pids=$(pgrep -f "$key" || true)
  if [ -n "$pids" ]; then
    log "kill $key  pids=$pids"
    kill $pids 2>/dev/null || true
    sleep 2
    pids=$(pgrep -f "$key" || true)
    [ -n "$pids" ] && { log "force kill -9 $pids"; kill -9 $pids 2>/dev/null || true; }
  fi
}

start_one() {
  local name=$1 jar=$2; shift 2
  local args=("$@")
  if [ ! -f "$jar" ]; then
    err "$name jar 不存在: $jar  请先 mvn package"
    return 1
  fi
  log "start $name"
  nohup java -jar "$jar" "${args[@]}" > "$LOG_DIR/${name}.out" 2>&1 &
  disown
  log "  pid=$!  log=$LOG_DIR/${name}.out"
}

precheck_redis() {
  if command -v redis-cli >/dev/null 2>&1; then
    local out
    out=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" -n "$REDIS_DATABASE" ping 2>&1 || true)
    if [[ "$out" == *PONG* ]]; then
      log "redis OK ($REDIS_HOST:$REDIS_PORT db=$REDIS_DATABASE)"
    else
      err "redis 连接失败: $out"
      err "  请确认: 1) host/port  2) password  3) 安全组放行  4) bind 配置"
      err "  仍继续启动，但服务会报 RedisConnectionFailureException"
    fi
  else
    log "(skip redis precheck: redis-cli not installed)"
  fi
}

healthcheck() {
  log "等待服务起来 ..."
  sleep 8
  echo
  log "==== 端口监听 ===="
  ss -tlnp 2>/dev/null | grep -E ":$PLATFORM_PORT|:$SERVER_PORT" || echo "(未检测到端口监听，看下面日志)"
  echo
  log "==== HTTP 健康 ===="
  curl -sS -o /dev/null -w "im-platform $PLATFORM_PORT : HTTP %{http_code}  time=%{time_total}s\n" \
    --max-time 5 "http://127.0.0.1:$PLATFORM_PORT/" || echo "im-platform 无响应"
  echo
  log "==== 关键日志（最近 ERROR/WARN）===="
  for f in "$LOG_DIR/im-platform.out" "$LOG_DIR/im-server.out"; do
    echo "--- $f"
    grep -E "Started .*Application|ERROR|WARN|Connection refused|NOAUTH|WRONGPASS|profile is active" "$f" 2>/dev/null | tail -5
  done
}

do_start() {
  precheck_redis
  start_one "im-platform" "$PLATFORM_JAR" "${PLATFORM_ARGS[@]}"
  start_one "im-server"   "$SERVER_JAR"   "${SERVER_ARGS[@]}"
  healthcheck
  echo
  log "如需跟踪日志: ./start.sh logs"
}

do_stop() {
  kill_one "im-platform.jar"
  kill_one "im-server.jar"
  log "已停止"
}

do_status() {
  echo "==== 进程 ===="
  pgrep -af "im-platform.jar|im-server.jar" || echo "(无进程)"
  echo
  echo "==== 端口 ===="
  ss -tlnp 2>/dev/null | grep -E ":$PLATFORM_PORT|:$SERVER_PORT" || echo "(无监听)"
}

do_logs() {
  tail -f "$LOG_DIR/im-platform.out" "$LOG_DIR/im-server.out"
}

# ============================================================
# 4) 入口
# ============================================================
case "${1:-start}" in
  start)   do_start ;;
  stop)    do_stop ;;
  restart) do_stop; sleep 1; do_start ;;
  status)  do_status ;;
  logs)    do_logs ;;
  *)
    echo "usage: $0 {start|stop|restart|status|logs}"
    exit 1
    ;;
esac
