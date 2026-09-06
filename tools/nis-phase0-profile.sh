#!/usr/bin/env bash

set -euo pipefail

usage() {
	local exit_code=2
	if [[ $# -gt 0 ]]; then
		exit_code=$1
	fi
	cat >&2 <<'EOF'
Usage:
  nis-phase0-profile.sh [--heap-dump] [--jfr SECONDS] PID OUTPUT_DIR

The output directory should be outside the repository. Heap dumps can be very
large and can trigger a stop-the-world collection, so --heap-dump is explicit.
--jfr starts an asynchronous JFR recording; the JVM writes the file when the
requested duration expires.
EOF
	exit "$exit_code"
}

heap_dump=false
jfr_seconds=

while [[ $# -gt 0 ]]; do
	case "$1" in
		--heap-dump)
			heap_dump=true
			shift
			;;
		--jfr)
			[[ $# -ge 2 ]] || usage
			jfr_seconds=$2
			[[ "$jfr_seconds" =~ ^[1-9][0-9]*$ ]] || usage
			shift 2
			;;
		--help|-h)
			usage 0
			;;
		*)
			break
			;;
	esac
done

[[ $# -eq 2 ]] || usage
pid=$1
output_dir=$2
[[ "$pid" =~ ^[1-9][0-9]*$ ]] || usage
[[ -r "/proc/$pid/cmdline" ]] || {
	echo "cannot read process $pid" >&2
	exit 1
}

command -v jcmd >/dev/null || {
	echo "jcmd is required" >&2
	exit 1
}
command -v jstat >/dev/null || {
	echo "jstat is required" >&2
	exit 1
}
command -v ps >/dev/null || {
	echo "ps is required" >&2
	exit 1
}

process_command=$(tr '\0' ' ' < "/proc/$pid/cmdline")
case "$process_command" in
	*java*|*Java*) ;;
	*)
		echo "refusing non-Java process $pid: $process_command" >&2
		exit 1
		;;
esac

mkdir -p "$output_dir"
output_dir=$(cd "$output_dir" && pwd)

date -u +"captured_at=%Y-%m-%dT%H:%M:%SZ" > "$output_dir/metadata.txt"
printf 'pid=%s\ncommand=%s\n' "$pid" "$process_command" >> "$output_dir/metadata.txt"
ps -o pid,ppid,rss,vsz,etime,stat,args -p "$pid" > "$output_dir/process.txt"
sed -n '/^Vm\(Peak\|Size\|RSS\|HWM\|Swap\):/p' "/proc/$pid/status" > "$output_dir/proc-status-memory.txt"
if [[ -r "/proc/$pid/smaps_rollup" ]]; then
	sed -n '/^\(Rss\|Pss\|Private\|Shared\|Swap\)/p' "/proc/$pid/smaps_rollup" > "$output_dir/smaps-rollup.txt"
fi

jcmd "$pid" VM.command_line > "$output_dir/jvm-command-line.txt"
jcmd "$pid" GC.heap_info > "$output_dir/gc-heap-info.txt"
jcmd "$pid" VM.metaspace > "$output_dir/metaspace.txt"
jcmd "$pid" GC.class_histogram > "$output_dir/class-histogram.txt"
sed -n '1,100p' "$output_dir/class-histogram.txt" > "$output_dir/class-histogram-top100.txt"
jstat -gc "$pid" > "$output_dir/jstat-gc.txt"
jstat -gcutil "$pid" > "$output_dir/jstat-gcutil.txt"

if jcmd "$pid" VM.native_memory summary > "$output_dir/native-memory.txt" 2>&1; then
	:
else
	echo "NMT is unavailable; restart with -XX:NativeMemoryTracking=summary to enable it." \
		>> "$output_dir/native-memory.txt"
fi

if [[ -n "$jfr_seconds" ]]; then
	jcmd "$pid" JFR.start \
		name=nis-phase0 \
		settings=profile \
		duration="${jfr_seconds}s" \
		filename="$output_dir/nis-phase0.jfr" \
		> "$output_dir/jfr-start.txt"
	echo "JFR recording started; it will finish after ${jfr_seconds}s." \
		> "$output_dir/jfr-status.txt"
	jcmd "$pid" JFR.check >> "$output_dir/jfr-status.txt"
fi

if [[ "$heap_dump" == true ]]; then
	jcmd "$pid" GC.heap_dump "$output_dir/heap.hprof" > "$output_dir/heap-dump.txt"
fi

echo "profile written to $output_dir"
