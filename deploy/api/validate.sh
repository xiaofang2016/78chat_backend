#!/bin/bash
set -euo pipefail
ready=0
for _ in $(seq 1 40); do
  body=$(curl -fsS "http://127.0.0.1:18889/api/wallet?merchantNo=10011" || true)
  if printf '%s' "$body" | grep -q "未登录"; then
    ready=1
    break
  fi
  sleep 3
done
if [ "$ready" != 1 ]; then
  docker compose -p zhiquan-api -f /var/local/zhiquan-api/docker-compose.api.yml logs --tail 80 >&2 || true
  exit 1
fi
