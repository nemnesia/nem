#!/bin/bash

set -ex

# Own an isolated loopback NIS runtime for every direct or Jenkins test run.
if [ ! -f target/nis-it-state ]; then
	./scripts/ci/setup_test.sh
fi

IT_HOME=$(cat target/nis-it-state)
cleanup() {
	status=$?
	trap - EXIT
	if ./scripts/ci/teardown_test.sh; then
		cleanup_status=0
	else
		cleanup_status=$?
	fi
	if [ "$status" -ne 0 ]; then
		exit "$status"
	fi
	exit "$cleanup_status"
}
trap cleanup EXIT

# Keep Surefire and Failsafe in one Maven invocation so plugin dependency
# properties (including the Mockito javaagent path) are resolved consistently.
# Run failsafe:verify so integration-test failures fail the Jenkins stage.
mvn test failsafe:integration-test failsafe:verify -B -Dnis.it.home="$IT_HOME"
