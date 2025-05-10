#!/bin/bash

set -eo pipefail
trap cleanUp INT

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source "${DIR}/lib.sh"

# Read Jar to JAR_PATH, move to tmp
uploadJar
# Create data
createData
# Start cluster
startCluster
# Start a job
startJob
echo "Started job $JOB_ID"

# Observe status on the leader
while true; do
  status=$(curl -sS --location "http://localhost:$LEADER_PORT/status/$JOB_ID")
  echo "${status}"
  typ=$(echo "$status" | jq '.type')
  if [[ $typ != '"NotStarted"' && $typ != '"InProgress"' && $typ != '"Halted"' ]]; then
    echo "Job done with $typ"
    break
  fi
  echo "Worker 1"
  printWorkerState $WORKER1_PORT
  echo "Worker 2"
  printWorkerState $WORKER2_PORT
  echo "Sleeping for $sleep seconds"
  sleep $sleep
done

# Get data
echo "Checking results"
curl -sS --location "http://localhost:$LEADER_PORT/data/$JOB_ID" | jq 'to_entries | sort_by(-.value) | from_entries' | head -n 5

# Cleanup
cleanUp
