#!/usr/bin/env bash
# 本地验证：知识库关闭 / Embedding 失败时不拖垮 AI 客服编排
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/im-platform"
mvn test -Dtest=AiKnowledgeBaseServiceImplKbGuardTest "$@"
