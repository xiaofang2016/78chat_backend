#!/bin/bash
set -euo pipefail
cd /var/local/zhiquan-api
if [ ! -f .env ]; then
  echo "缺少 /var/local/zhiquan-api/.env，先按 env.example 写上数据库和 Redis 口令" >&2
  exit 1
fi
# jar 是挂载进去的。compose 看不到文件内容变化，不重建的话旧进程会一直跑。
docker compose -p zhiquan-api -f /var/local/zhiquan-api/docker-compose.api.yml --project-directory /var/local/zhiquan-api up -d --force-recreate --remove-orphans
