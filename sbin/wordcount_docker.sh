#!/bin/bash

set -eou pipefail
#trap cleanUp INT

function createData() {
  rm -rf /tmp/data/
  mkdir -p /tmp/data/in
  mkdir -p /tmp/data/out
  if [[ ! -f "/tmp/war_and_peace.txt" ]]; then
    wget https://www.gutenberg.org/cache/epub/2600/pg2600.txt -O /tmp/war_and_peace.txt
  fi
  # 66030 lines => 4 workers => ~16500 lines per worker
  split -l 16500 /tmp/war_and_peace.txt /tmp/data/in/war_and_peace.txt.
}


function startJob() {
  start=$(curl -sS --location "http://localhost:$LEADER_PORT/start" \
    --header 'Content-Type: application/json' \
    --data '{
                        "name": "Example job",
                        "jobClass": {
                            "type": "DelayedWordCountJob"
                        },
                        "input": "/tmp/in",
                        "output": "/tmp/out",
                        "userSettings": {"timeout": "2"}
                    }')
  JOB_ID=$(echo "$start" | jq '.jobId')
  echo "${start}"
}

function cleanUp() {
  echo "Cleaning up"
  docker-compose down
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

# Start cluster
docker-compose up -d

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
cleanUp
