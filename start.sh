#!/usr/bin/env sh
# 文件说明：start.sh：项目自动化脚本；按脚本参数执行对应任务。
set -eu
cd "$(dirname "$0")"
if [ ! -f .env ]; then
  cp .env.example .env
  echo "WARNING: created .env from .env.example; change credentials before public deployment." >&2
fi
docker compose up -d --build
docker compose ps
printf '\nAdmin:   http://localhost:38008/admin/\nSwagger: http://localhost:38008/swagger-ui.html\nHealth:  http://localhost:38008/actuator/health\n'
