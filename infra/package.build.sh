#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
	printf 'Usage: %s <logfile>\n' "$0" >&2
	exit 2
fi
logfile=$1

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"
echo " [+] STARTING BUILD (this might take some time) $logfile"
rm -rf core/src/main/resources/nemesis-mijin*
rm -rf core/target/classes/nemesis-mijin*
rm -rf nis/src/main/resources/*mijin*
rm -rf nis/target/classes/*mijin*

mvn clean install -DskipTests=true 2>&1 | tee -a "$logfile"
cd core
echo " [+] CREATING DOCS"
mvn javadoc:javadoc >>"$logfile" 2>&1
