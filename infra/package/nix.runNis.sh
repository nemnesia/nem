#!/bin/sh
set -eu

script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$script_dir/nis"
exec java -Xms4G -Xmx6G -cp '.:./*:../libs/*' org.nem.deploy.CommonStarter
