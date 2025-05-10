#!/bin/bash

set -eo pipefail
trap cleanUp INT

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source "${DIR}/lib.sh"

function killOnPort() {
  local port=$1
  echo "Killing on port $port"
  docker stop $(docker ps -q --filter "publish=$port")
}

# murder counter
i=0

function killIf() {
  local worker=$1
  local port=$2
  local thres=$3
  local i=$4
  if [[ $i -lt $thres ]]; then
    echo "Worker $worker"
    printWorkerState $port
  fi
  if [[ $i -eq $thres ]]; then
    killOnPort $port
  fi
}

# Read Jar to JAR_PATH, move to tmp
uploadJar
# Create data
createData
# Start one leader, 2 worker
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
    echo "Job done"
    break
  fi

  if [[ -z $(docker ps | grep spren) ]]; then
    echo "Restarting "
    docker start bridgefour-spren-01-1
  fi

  killIf 1 $WORKER1_PORT 1 $i
  killIf 2 $WORKER2_PORT 2 $i

  i=$((i + 1))
  if [[ $i -gt 2 ]]; then
    sleep=20
  fi

  echo "Sleeping for $sleep seconds"
  sleep $sleep
done

# Get data
echo "Checking results"
curl -sS --location "http://localhost:$LEADER_PORT/data/$JOB_ID" | jq 'to_entries | sort_by(-.value) | from_entries' | head -n 5

# Cleanup
cleanUp
