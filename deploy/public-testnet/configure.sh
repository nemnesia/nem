#!/usr/bin/env bash
set -euo pipefail

config_dir=${NIS_TESTNET_CONFIG_DIR:-/etc/nis-public-testnet}
data_dir=${NIS_TESTNET_DATA_DIR:-/var/lib/nis-public-testnet}
bundle_dir=${NIS_TESTNET_BUNDLE_DIR:-/opt/nis-public-testnet}
public_host=${NIS_TESTNET_PUBLIC_HOST:-}

if [[ ! "$public_host" =~ ^[A-Za-z0-9.-]+$ || "$public_host" == *REPLACE* ]]; then
	echo "Set NIS_TESTNET_PUBLIC_HOST to the public DNS name or IPv4 endpoint host." >&2
	exit 1
fi
if [[ ! -d "$bundle_dir/nis" || ! -f "$bundle_dir/ops/config.properties.example" ]]; then
	echo "NIS bundle not found at $bundle_dir; build it with build-bundle.sh first." >&2
	exit 1
fi

umask 077
mkdir -p "$config_dir" "$data_dir"
if [[ ! -f "$config_dir/node.private-key" ]]; then
	command -v openssl >/dev/null
	openssl rand -hex 32 > "$config_dir/node.private-key"
fi
chmod 0600 "$config_dir/node.private-key"
private_key=$(tr -d '\r\n' < "$config_dir/node.private-key")
if [[ ! "$private_key" =~ ^[[:xdigit:]]{64}$ ]]; then
	echo "The Testnet node key file must contain exactly 32 bytes as 64 hex characters." >&2
	exit 1
fi

sed "s/REPLACE_WITH_PUBLIC_HOST/$public_host/; s|/var/lib/nis-public-testnet|$data_dir|g" \
	"$bundle_dir/ops/config.properties.example" > "$config_dir/config.properties"
chmod 0644 "$config_dir/config.properties"
{
	printf 'nis.bootKey = %s\n' "$private_key"
	printf 'nis.bootName = nis-java25-public-testnet\n'
	printf 'nis.shouldAutoBoot = true\n'
	printf 'nis.shouldAutoHarvestOnBoot = false\n'
} > "$config_dir/config-user.properties"
chmod 0600 "$config_dir/config-user.properties"
unset private_key
install -m 0644 "$bundle_dir/ops/db.properties" "$config_dir/db.properties"
echo "Testnet-only configuration created in $config_dir; key and database are outside the repository."
