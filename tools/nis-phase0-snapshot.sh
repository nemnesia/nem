#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 ]]; then
	echo "Usage: NIS_BASE_URL=http://127.0.0.1:7890 NIS_SNAPSHOT_ADDRESSES='A B' $0 OUTPUT_DIR" >&2
	exit 2
fi

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }

output_dir=$1
mkdir -p "$output_dir"
output_dir=$(cd "$output_dir" && pwd)
base_url=${NIS_BASE_URL:-http://127.0.0.1:7890}
base_url=${base_url%/}
page_size=${NIS_SNAPSHOT_PAGE_SIZE:-1000}

canonical_json() {
	jq -S 'walk(if type == "array" then sort_by(tostring) else . end)'
}

get_json() {
	local file_name=$1
	local path=$2
	shift 2
	curl --fail --silent --show-error --get "$base_url$path" "$@" | canonical_json > "$output_dir/$file_name.json"
}

safe_name() {
	local value=$1
	printf '%s' "${value//[^A-Za-z0-9_.-]/_}"
}

{
	printf 'captured_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
	printf 'base_url=%s\n' "$base_url"
	printf 'page_size=%s\n' "$page_size"
	printf 'addresses=%s\n' "${NIS_SNAPSHOT_ADDRESSES:-}"
	printf 'namespaces=%s\n' "${NIS_SNAPSHOT_NAMESPACES:-}"
	printf 'mosaics=%s\n' "${NIS_SNAPSHOT_MOSAICS:-}"
	printf 'height=%s\n' "${NIS_SNAPSHOT_HEIGHT:-}"
} > "$output_dir/manifest.txt"

get_json chain-height /chain/height
get_json chain-last-block /chain/last-block
get_json chain-score /chain/score
get_json account-importances /account/importances
get_json namespace-roots "/namespace/root/page?id=0&pageSize=$page_size"
get_json mosaic-definitions "/mosaic/definition/page?id=0&pageSize=$page_size"

read -r -a addresses <<< "${NIS_SNAPSHOT_ADDRESSES:-}"
for address in "${addresses[@]}"; do
	[[ -n "$address" ]] || continue
	name=$(safe_name "$address")
	get_json "account-$name" /account/get --data-urlencode "address=$address"
	get_json "account-$name-forwarded" /account/get/forwarded --data-urlencode "address=$address"
	get_json "account-$name-status" /account/status --data-urlencode "address=$address"
done

read -r -a namespaces <<< "${NIS_SNAPSHOT_NAMESPACES:-}"
for namespace in "${namespaces[@]}"; do
	[[ -n "$namespace" ]] || continue
	name=$(safe_name "$namespace")
	get_json "namespace-$name" /namespace --data-urlencode "namespace=$namespace"
done

read -r -a mosaics <<< "${NIS_SNAPSHOT_MOSAICS:-}"
for mosaic in "${mosaics[@]}"; do
	[[ -n "$mosaic" ]] || continue
	name=$(safe_name "$mosaic")
	get_json "mosaic-$name-supply" /mosaic/supply --data-urlencode "mosaicId=$mosaic"
	get_json "mosaic-$name-definition-last" /mosaic/definition/last --data-urlencode "mosaicId=$mosaic"
	if [[ -n "${NIS_SNAPSHOT_HEIGHT:-}" ]]; then
		get_json "mosaic-$name-definition-supply" /local/mosaic/definition/supply \
			--data-urlencode "mosaicId=$mosaic" \
			--data-urlencode "height=$NIS_SNAPSHOT_HEIGHT"
	fi
done

if [[ -n "${NIS_SNAPSHOT_HEIGHT:-}" ]]; then
	get_json "expired-mosaics-${NIS_SNAPSHOT_HEIGHT}" /local/mosaics/expired \
		--data-urlencode "height=$NIS_SNAPSHOT_HEIGHT"
fi

(cd "$output_dir" && sha256sum -- *.json) > "$output_dir/SHA256SUMS"
echo "snapshot written to $output_dir"
