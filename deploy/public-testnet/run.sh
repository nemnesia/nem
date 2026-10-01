#!/usr/bin/env bash
set -euo pipefail
umask 077

bundle_dir=${NIS_TESTNET_BUNDLE_DIR:-/opt/nis-public-testnet}
config_dir=${NIS_TESTNET_CONFIG_DIR:-/etc/nis-public-testnet}

if [[ ! -r "$config_dir/config.properties" || ! -r "$config_dir/config-user.properties" ]]; then
	echo "Missing Testnet configuration in $config_dir; run ops/configure.sh." >&2
	exit 1
fi
if [[ ! -r "$bundle_dir/nis/peers-config_testnet.json" ]]; then
	echo "Missing shipped Testnet peer configuration in $bundle_dir/nis." >&2
	exit 1
fi
java_version=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
if [[ "$java_version" != 25 ]]; then
	echo "Java 25 is required; found ${java_version:-unknown}" >&2
	exit 1
fi

xms=${NIS_JAVA_XMS:-2g}
xmx=${NIS_JAVA_XMX:-6g}
if [[ ! "$xms" =~ ^[1-9][0-9]*[mMgG]$ || ! "$xmx" =~ ^[1-9][0-9]*[mMgG]$ ]]; then
	echo "NIS_JAVA_XMS and NIS_JAVA_XMX must be sizes such as 2g and 6g." >&2
	exit 1
fi
log_dir=${NIS_TESTNET_LOG_DIR:-/var/lib/nis-public-testnet/nis/logs}
mkdir -p "$log_dir"
cd "$bundle_dir"
exec java "-Xms$xms" "-Xmx$xmx" -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
	-Xlog:gc*:file="$log_dir/gc.log":time,uptime,level,tags \
	-cp "$config_dir:$bundle_dir/nis/*:$bundle_dir/libs/*" org.nem.deploy.CommonStarter
