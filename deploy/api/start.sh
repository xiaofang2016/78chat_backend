#!/bin/bash
set -euo pipefail
cd /var/local/zhiquan-api
if [ ! -f env-name ]; then
  echo "缺少 /var/local/zhiquan-api/env-name，内容写 production 或 test" >&2
  exit 1
fi
ENV_NAME=$(tr -d '[:space:]' < env-name)
case "$ENV_NAME" in
  production|test) ;;
  *)
    echo "env-name 只能是 production 或 test，当前是 ${ENV_NAME}" >&2
    exit 1
    ;;
esac
aws ssm get-parameter --region ap-northeast-1 \
  --name "/zhiquan/im-platform/${ENV_NAME}" \
  --with-decryption \
  --query Parameter.Value \
  --output text > .env.tmp
mv .env.tmp .env
chmod 600 .env
# Image is only a Java runtime. The jar is mounted in. Build once on a new
# machine so Compose does not pull 78chat-im-platform:local from Docker Hub.
if ! docker image inspect 78chat-im-platform:local >/dev/null 2>&1; then
  docker build -t 78chat-im-platform:local -f Dockerfile .
fi
docker compose -p zhiquan-api -f /var/local/zhiquan-api/docker-compose.api.yml --project-directory /var/local/zhiquan-api up -d --force-recreate --remove-orphans