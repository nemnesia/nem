#!/bin/bash

set -ex

# Keep Surefire and Failsafe in one Maven invocation so plugin dependency
# properties (including the Mockito javaagent path) are resolved consistently.
# Run failsafe:verify so integration-test failures fail the Jenkins stage.
mvn test failsafe:integration-test failsafe:verify -B
