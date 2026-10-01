#!/bin/bash

set -euo pipefail

cd "$(dirname "$0")/../.."
mkdir -p target
STATE_FILE=target/nis-it-state

is_ready() {
	curl --fail --silent --max-time 2 http://127.0.0.1:7890/heartbeat >/dev/null 2>&1 \
		&& curl --fail --silent --max-time 2 -H 'Content-Type: application/json' \
			-d '{"height":1}' http://127.0.0.1:7890/block/at/public >/dev/null 2>&1
}

if [ -f "$STATE_FILE" ]; then
	EXISTING_HOME=$(cat "$STATE_FILE")
	if [ -f "$EXISTING_HOME/nis.pid" ] && kill -0 "$(cat "$EXISTING_HOME/nis.pid")" 2>/dev/null \
		&& is_ready; then
		echo "NIS integration runtime is already ready."
		exit 0
	fi
	./scripts/ci/teardown_test.sh
fi

if curl --fail --silent --max-time 2 http://127.0.0.1:7890/heartbeat >/dev/null 2>&1; then
	echo "Refusing to use an NIS service already listening on 127.0.0.1:7890." >&2
	exit 1
fi

IT_HOME=$(mktemp -d "$PWD/target/nis-it.XXXXXX")
cleanup_unstarted() {
	status=$?
	trap - EXIT
	if [ ! -f "$IT_HOME/nis.pid" ]; then
		rm -rf -- "$IT_HOME"
	fi
	exit "$status"
}
trap cleanup_unstarted EXIT
CONFIG_DIR="$IT_HOME/classpath"
mkdir -p "$CONFIG_DIR" "$IT_HOME/nem/nis/data"
cat > "$CONFIG_DIR/config.properties" <<EOF
nem.folder=$IT_HOME/nem
nem.host=127.0.0.1
nem.httpPort=7890
nem.websocketPort=0
nem.network=testnet
nis.shouldAutoBoot=false
nis.shouldAutoHarvestOnBoot=false
nis.nodeLimit=0
nis.timeSyncNodeLimit=0
nis.ipDetectionMode=Disabled
nis.delayBlockLoading=false
nis.useNetworkTime=false
EOF

# A separate NIS runtime-derived Mijin dataset is required only by
# MissingTransactionITCase. Copy it into this disposable home when supplied.
if [ -n "${NIS_IT_MIJINNET_DB:-}" ]; then
	if [ ! -f "$NIS_IT_MIJINNET_DB" ]; then
		echo "NIS_IT_MIJINNET_DB does not name a readable H2 database file." >&2
		exit 1
	fi
	cp -- "$NIS_IT_MIJINNET_DB" "$IT_HOME/nem/nis/data/nis5_mijinnet.mv.db"
fi

mvn -B -q dependency:build-classpath -Dmdep.outputFile=target/nis-it-classpath.txt
DEPENDENCY_CLASSPATH=$(cat target/nis-it-classpath.txt)
mvn -B -q -f ../deploy/pom.xml dependency:build-classpath -Dmdep.outputFile="$PWD/target/deploy-it-classpath.txt"
DEPLOY_CLASSPATH=$(cat target/deploy-it-classpath.txt)
RUNTIME_CLASSPATH="$CONFIG_DIR:target/classes:../core/target/classes:../deploy/target/classes:../peer/target/classes:$DEPENDENCY_CLASSPATH:$DEPLOY_CLASSPATH"

nohup java -Duser.home="$IT_HOME" -cp "$RUNTIME_CLASSPATH" org.nem.deploy.CommonStarter \
	</dev/null > "$IT_HOME/nis.log" 2>&1 &
NIS_PID=$!
printf '%s\n' "$IT_HOME" > "$STATE_FILE"
printf '%s\n' "$NIS_PID" > "$IT_HOME/nis.pid"

for attempt in $(seq 1 90); do
	if is_ready; then
		echo "Isolated Testnet NIS runtime ready at http://127.0.0.1:7890 (home: $IT_HOME)."
		trap - EXIT
		exit 0
	fi
	if ! kill -0 "$NIS_PID" 2>/dev/null; then
		echo "Isolated NIS runtime exited before readiness:" >&2
		cp "$IT_HOME/nis.log" target/nis-it-startup-failure.log
		cat "$IT_HOME/nis.log" >&2
		./scripts/ci/teardown_test.sh
		exit 1
	fi
	sleep 1
done

echo "Isolated NIS runtime did not become ready within 90 seconds:" >&2
cp "$IT_HOME/nis.log" target/nis-it-startup-failure.log
cat "$IT_HOME/nis.log" >&2
./scripts/ci/teardown_test.sh
exit 1
