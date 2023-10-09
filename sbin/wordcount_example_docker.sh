#!/bin/bash

set -eou pipefail
trap stopCluster ERR INT

BRIDGEFOUR_ROOT="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

function createData() {
  mkdir -p /tmp/data
  mkdir -p /tmp/in
  rm -rf /tmp/in/*
  mkdir -p /tmp/out
  rm -rf /tmp/out/*
  if [[ ! -f "/tmp/data/war_and_peace.txt" ]]; then
    wget https://www.gutenberg.org/cache/epub/2600/pg2600.txt -O /tmp/data/war_and_peace.txt
  fi
  # 66030 lines => 4 workers => ~16500 lines per worker
  split -l 16500 /tmp/data/war_and_peace.txt /tmp/data/war_and_peace.txt.
  mv /tmp/data/war_and_peace.txt.a* /tmp/in
}

function startCluster() {
  cd "${BRIDGEFOUR_ROOT}"
  docker-compose up -d
}


function stopCluster() {
  echo "Cleaning up"
  cd "${BRIDGEFOUR_ROOT}"
  docker-compose down
}


function startJob() {
  echo "Starting job on $LEADER_PORT"
  start=$(curl -sS --location "http://localhost:$LEADER_PORT/start" \
    --header 'Content-Type: application/json' \
    --data '{
                        "name": "Example job",
                        "jobClass": {
                            "type": "DelayedWordCountJob"
                        },
                        "input": "/data/in",
                        "output": "/data/out",
                        "userSettings": {"timeout": "2"}
                    }')
  JOB_ID=$(echo "$start" | jq '.jobId')
  echo "${start}"
}

function printWorkerState() {
  local port=$1
  echo $(curl -Ss --location "http://localhost:$port/worker/state")
}

sleep=10
LEADER_PORT=5550
WORKER1_PORT=5551
WORKER2_PORT=5552

# Create data
createData
# Start one leader, 2 worker
startCluster
# Wait for it to come online
echo "Waiting"
sleep 5

# Start a job
startJob
echo "Started job $JOB_ID"

# Observe status on the leader
while true; do
  status=$(curl -sS --location "http://localhost:$LEADER_PORT/refresh/$JOB_ID")
  echo "${status}"
  typ=$(echo "$status" | jq '.type')
  if [[ $typ != '"InProgress"' && $typ != '"Halted"' ]]; then
    echo "Job done"
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
stopCluster
