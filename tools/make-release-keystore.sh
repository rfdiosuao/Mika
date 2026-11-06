#!/usr/bin/env bash
# 生成 Lom 版 Mika 的正式签名密钥库(只需要在发布者本机跑一次)。
# - 密钥库放在仓库之外:~/.config/lom/mika-release/(目录 700,文件 600)
# - 密码随机生成,只存进 macOS 钥匙串(服务名 top.heang.lom.mika.release),不写文件、不打印
# - 丢了密钥库就无法给已安装用户推送升级:务必备份 mika-lom-release.p12 并把钥匙串里的密码一起备份
set -euo pipefail
DIR="$HOME/.config/lom/mika-release"
STORE="$DIR/mika-lom-release.p12"
SERVICE="top.heang.lom.mika.release"
[ -e "$STORE" ] && { echo "已存在 $STORE,不覆盖。" >&2; exit 1; }
umask 077
mkdir -p "$DIR"; chmod 700 "$HOME/.config/lom" "$DIR"
PW="$(openssl rand -base64 33 | tr -d '/+=\n' | head -c 40)"
security add-generic-password -a mika-lom-release -s "$SERVICE" -w "$PW" -U
KSPW="$PW" keytool -genkeypair -keystore "$STORE" -storetype PKCS12 -alias mika-lom-release \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Lom Mika, O=Lom, C=CN" \
  -storepass:env KSPW -keypass:env KSPW >/dev/null
chmod 600 "$STORE"
echo "已生成 $STORE"
KSPW="$(security find-generic-password -s "$SERVICE" -w)" keytool -list -v -keystore "$STORE" -storepass:env KSPW 2>/dev/null \
  | grep -E "Alias name|Valid from|SHA256"
