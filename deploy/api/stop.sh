#!/bin/bash
set -euo pipefail
if [ ! -f /var/local/zhiquan-api/docker-compose.api.yml ]; then
  exit 0
fi
docker compose -p zhiquan-api -f /var/local/zhiquan-api/docker-compose.api.yml --project-directory /var/local/zhiquan-api down
