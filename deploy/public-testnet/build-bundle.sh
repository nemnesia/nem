#!/usr/bin/env bash
set -euo pipefail

repo_root=$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)
output_dir=${1:?usage: build-bundle.sh OUTPUT_DIR}

java_version=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
if [[ "$java_version" != 25 ]]; then
	echo "Java 25 is required; found ${java_version:-unknown}" >&2
	exit 1
fi
if [[ -n "${JAVA_HOME:-}" && ! -x "$JAVA_HOME/bin/java" ]]; then
	unset JAVA_HOME
fi

mkdir -p "$output_dir"
if find "$output_dir" -mindepth 1 -print -quit | grep -q .; then
	echo "Output directory must be empty: $output_dir" >&2
	exit 1
fi

(
	cd "$repo_root"
	mvn -B -pl nis -am package
)

mkdir -p "$output_dir/nis" "$output_dir/libs" "$output_dir/ops"
cp "$repo_root"/nis/target/nem-infrastructure-server-*.jar "$output_dir/nis/"
cp "$repo_root"/nis/target/libs/*.jar "$output_dir/libs/"
for jar in "$output_dir"/libs/nem-*.jar; do
	[[ -e "$jar" ]] || continue
	mv "$jar" "$output_dir/nis/"
done
cp "$repo_root"/nis/src/main/resources/peers-config_testnet.json "$output_dir/nis/"
cp "$repo_root"/infra/package/nis/db.properties "$output_dir/ops/"
cp "$(dirname "${BASH_SOURCE[0]}")"/config.properties.example "$output_dir/ops/"
cp "$(dirname "${BASH_SOURCE[0]}")"/configure.sh "$output_dir/ops/"
cp "$(dirname "${BASH_SOURCE[0]}")"/nis-public-testnet.service "$output_dir/ops/"
cp "$(dirname "${BASH_SOURCE[0]}")"/README.md "$output_dir/ops/"
cp "$(dirname "${BASH_SOURCE[0]}")"/run.sh "$output_dir/"
cp "$(dirname "${BASH_SOURCE[0]}")"/check.sh "$output_dir/"
cp "$(dirname "${BASH_SOURCE[0]}")"/check-public.sh "$output_dir/"
cp "$(dirname "${BASH_SOURCE[0]}")"/monitor-tip.sh "$output_dir/"
chmod 0755 "$output_dir"/*.sh "$output_dir/ops/configure.sh"
echo "Built NIS $(basename "$output_dir"), Java $java_version; bundle at $output_dir"
