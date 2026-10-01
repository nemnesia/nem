#!/bin/bash

set -euo pipefail

cd "$(dirname "$0")/../.."
STATE_FILE=target/nis-it-state
[ -f "$STATE_FILE" ] || exit 0
IT_HOME=$(cat "$STATE_FILE")

case "$IT_HOME" in
	"$PWD"/target/nis-it.*) ;;
	*) echo "Refusing to remove unexpected NIS test home: $IT_HOME" >&2; exit 1 ;;
esac

if [ -f "$IT_HOME/nis.pid" ]; then
	NIS_PID=$(cat "$IT_HOME/nis.pid")
	if kill -0 "$NIS_PID" 2>/dev/null; then
		if ! curl --silent --max-time 3 http://127.0.0.1:7890/shutdown >/dev/null 2>&1; then
			echo "NIS shutdown endpoint did not respond; checking the tracked process." >&2
		fi
		for attempt in $(seq 1 20); do
			if ! kill -0 "$NIS_PID" 2>/dev/null; then
				break
			fi
			sleep 1
		done
		if kill -0 "$NIS_PID" 2>/dev/null; then
			if ! COMMAND=$(ps -p "$NIS_PID" -o args= 2>/dev/null); then
				COMMAND=
			fi
			if ! PROCESS_STATE=$(ps -p "$NIS_PID" -o stat= 2>/dev/null); then
				PROCESS_STATE=
			fi
			case "$COMMAND" in
				*org.nem.deploy.CommonStarter*)
					case "$PROCESS_STATE" in
						Z*) ;;
						*)
							kill "$NIS_PID"
							for attempt in $(seq 1 10); do
								if ! kill -0 "$NIS_PID" 2>/dev/null; then
									break
								fi
								sleep 1
							done
							if kill -0 "$NIS_PID" 2>/dev/null; then
								echo "Isolated NIS process $NIS_PID did not stop; leaving its home directory intact." >&2
								exit 1
							fi
							;;
						esac
					;;
				*) echo "NIS PID no longer identifies the launched CommonStarter; leaving it untouched." >&2 ;;
			esac
		fi
	fi
fi

rm -f "$STATE_FILE"
rm -rf -- "$IT_HOME"
