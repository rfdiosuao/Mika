#!/usr/bin/env bash
# 用钥匙串里的密码构建 Lom 版正式包。需要先跑过 tools/make-release-keystore.sh。
# 密码只经环境变量交给 Gradle,不写文件、不打印。
set -euo pipefail
cd "$(dirname "$0")/.."
export MIKA_KEYSTORE_FILE="$HOME/.config/lom/mika-release/mika-lom-release.p12"
export MIKA_KEY_ALIAS="mika-lom-release"
MIKA_KEYSTORE_PASSWORD="$(security find-generic-password -s top.heang.lom.mika.release -w)"
export MIKA_KEYSTORE_PASSWORD
exec ./gradlew :app:assembleRelease "$@"
