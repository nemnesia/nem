#!/usr/bin/env bash
set -euo pipefail

host=${1:?usage: check-public.sh PUBLIC_HOST}
port=${2:-7890}
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
base_url="http://$host:$port"
"$script_dir/check.sh" "$base_url"

classpath=${NIS_TESTNET_CLASSPATH:-"$script_dir/nis/*:$script_dir/libs/*"}
if [[ ! -d "$script_dir/nis" && -z "${NIS_TESTNET_CLASSPATH:-}" ]]; then
	echo "Run this checker from the built Testnet bundle or set NIS_TESTNET_CLASSPATH." >&2
	exit 1
fi
java -cp "$classpath" org.nem.nis.tools.PublicPeerHandshakeCheck "$host" "$port"
