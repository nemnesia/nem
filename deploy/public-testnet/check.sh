#!/usr/bin/env bash
set -euo pipefail

base_url=${1:-http://127.0.0.1:7890}
node_info=$(curl --fail --silent --show-error --max-time 10 "$base_url/node/info")
height=$(curl --fail --silent --show-error --max-time 10 "$base_url/chain/height")
peers=$(curl --fail --silent --show-error --max-time 10 "$base_url/node/peer-list/active")
status=$(curl --fail --silent --show-error --max-time 10 "$base_url/status")
python3 -c 'import json,sys
info=json.loads(sys.argv[1]); height=json.loads(sys.argv[2]); peers=json.loads(sys.argv[3]); status=json.loads(sys.argv[4])
meta=info.get("metaData", {})
if meta.get("networkId") != -104:
    raise SystemExit("NIS endpoint is not on NEM Testnet (expected networkId -104)")
print("networkId:", meta["networkId"])
print("version:", meta.get("version"))
print("advertised:", info.get("endpoint"))
print("height:", height.get("height"))
print("status:", status.get("code"), status.get("message"))
print("active peers:", len(peers.get("data", [])))' "$node_info" "$height" "$peers" "$status"
