#!/usr/bin/env sh
# 文件说明：stop.sh：项目自动化脚本；按脚本参数执行对应任务。
set -eu
cd "$(dirname "$0")"
docker compose down --remove-orphans
