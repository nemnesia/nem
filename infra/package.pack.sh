#!/bin/sh
set -eu

script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
package_dir="$script_dir/package"
server_jar=
server_jar_count=0

for jar in "$package_dir/nis/nem-infrastructure-server-"*.jar; do
	if [ -f "$jar" ]; then
		server_jar=$jar
		server_jar_count=$((server_jar_count + 1))
	fi
done

if [ "$server_jar_count" -ne 1 ]; then
	printf 'Expected exactly one NIS server jar in %s/nis, found %d\n' \
		"$package_dir" "$server_jar_count" >&2
	exit 1
fi

jar_name=${server_jar##*/}
version=${jar_name#nem-infrastructure-server-}
version=${version%.jar}
archive="$script_dir/nis-$version.tgz"

printf ' [+] CREATING %s\n' "$archive"
tar -czpf "$archive" -C "$script_dir" package
sha256sum "$archive"
