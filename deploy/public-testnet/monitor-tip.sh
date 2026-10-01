#!/usr/bin/env bash
set -euo pipefail

samples=${1:-6}
interval=${2:-300}
local_url=${3:-http://127.0.0.1:7890}
if [[ ! "$samples" =~ ^[1-9][0-9]*$ ]] || ((samples < 2)); then
	echo "samples must be an integer of at least 2" >&2
	exit 1
fi
if [[ ! "$interval" =~ ^[1-9][0-9]*$ ]]; then
	echo "interval must be a positive integer number of seconds" >&2
	exit 1
fi

peers=(
	tortuga.nemtest.net
	ocracoke.nemtest.net
	libertalia.nemtest.net
)
printf 'timestamp,local_height,%s\n' "$(IFS=,; echo "${peers[*]}")"
for ((sample = 1; sample <= samples; sample++)); do
	local_height=$(curl --fail --silent --show-error --max-time 10 "$local_url/chain/height" \
		| python3 -c 'import json,sys; print(json.load(sys.stdin)["height"])')
	remote_heights=()
	for peer in "${peers[@]}"; do
		height=$(curl --fail --silent --show-error --max-time 10 "http://$peer:7890/chain/height" \
			| python3 -c 'import json,sys; print(json.load(sys.stdin)["height"])')
		remote_heights+=("$height")
	done
	printf '%s,%s,%s\n' "$(date -u +%FT%TZ)" "$local_height" "$(IFS=,; echo "${remote_heights[*]}")"
	if ((sample < samples)); then
		sleep "$interval"
	fi
done
