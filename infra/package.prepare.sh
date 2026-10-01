#!/bin/sh
set -eu

script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
case $# in
	0) mode=mainnet ;;
	1) mode=$1 ;;
	*)
		printf 'Usage: %s [mainnet|testnet]\n' "$0" >&2
		exit 2
		;;
esac

case "$mode" in
	mainnet|testnet) ;;
	*)
		printf 'Unsupported network: %s (expected mainnet or testnet)\n' "$mode" >&2
		exit 2
		;;
esac

package_dir="$script_dir/package"
config_file="$package_dir/nis/config.properties"
runtime_lib_count=0
module_jar_count=0

for jar in "$script_dir/../nis/target/libs/"*.jar; do
	if [ -f "$jar" ]; then
		runtime_lib_count=$((runtime_lib_count + 1))
	fi
done
for jar in "$script_dir/../nis/target/"nem-*.jar "$script_dir/../nis/target/libs/"nem-*.jar; do
	if [ -f "$jar" ]; then
		module_jar_count=$((module_jar_count + 1))
	fi
done

if [ "$runtime_lib_count" -eq 0 ]; then
	printf 'No runtime libraries found in %s\n' "$script_dir/../nis/target/libs" >&2
	exit 1
fi
if [ "$module_jar_count" -eq 0 ]; then
	printf 'No NEM module jars found under %s/../nis/target\n' "$script_dir" >&2
	exit 1
fi
if [ ! -f "$config_file" ]; then
	printf 'Missing package configuration: %s\n' "$config_file" >&2
	exit 1
fi

mkdir -p "$package_dir/libs" "$package_dir/nis"
printf ' [+] CREATING >>%s<< PACKAGE\n' "$(printf '%s' "$mode" | tr '[:lower:]' '[:upper:]')"

for jar in "$package_dir/libs/"*.jar "$package_dir/nis/"*.jar; do
	if [ -f "$jar" ]; then
		rm -f "$jar"
	fi
done

for jar in "$script_dir/../nis/target/libs/"*.jar; do
	if [ -f "$jar" ]; then
		case "$jar" in
			*/nem-*) ;;
			*) cp "$jar" "$package_dir/libs/" ;;
		esac
	fi
done
for jar in "$script_dir/../nis/target/libs/"nem-*.jar "$script_dir/../nis/target/"nem-*.jar; do
	if [ -f "$jar" ]; then
		cp "$jar" "$package_dir/nis/"
	fi
done

sed "s/^nem\\.network[[:space:]]*=.*/nem.network = $mode/" "$config_file" > "$config_file.tmp"
mv "$config_file.tmp" "$config_file"
