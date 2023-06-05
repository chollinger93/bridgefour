#!/bin/bash

set -eou pipefail
#trap cleanUp INT

function createData() {
  mkdir -p /tmp/data
  mkdir -p /tmp/in
  rm -rf /tmp/in/*
  mkdir -p /tmp/out
  rm -rf /tmp/out/*
  wget https://www.gutenberg.org/cache/epub/2600/pg2600.txt -O /tmp/data/war_and_peace.txt
  # 66030 lines => 4 workers => ~16500 lines per worker
  split -l 16500 /tmp/data/war_and_peace.txt /tmp/data/war_and_peace.txt.
  mv /tmp/data/war_and_peace.txt.a* /tmp/in
  #touch /tmp/in/file{0..2}.csv
}

function startCluster() {
  WORKER1_PORT=$WORKER1_PORT WORKER2_PORT=$WORKER2_PORT sbt leader/run &
  LEADER_PID=$!
  echo "Started leader 1 [$LEADER_PID]"
  WORKER_PORT=$WORKER1_PORT sbt worker/run &
  WORKER_1_PID=$!
  echo "Started worker 1 [$WORKER_1_PID]"
  WORKER_PORT=$WORKER2_PORT sbt worker/run &
  WORKER_2_PID=$!
  echo "Started worker 2 [$WORKER_2_PID]"
}

function startJob() {
  start=$(curl -sS --location 'http://localhost:5555/start' \
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
  kill -9 $LEADER_PID
  kill -9 $WORKER_1_PID
  kill -9 $WORKER_2_PID
}

function printWorkerState() {
  local port=$1
  echo $(curl -Ss --location "http://localhost:$port/worker/state")
}

sleep=10
WORKER1_PORT=5554
WORKER2_PORT=5553

# Create data
createData
# Start one leader, 2 worker
#startCluster

# Start a job
startJob
echo "Started job $JOB_ID"

# Observe status on the leader
while true; do
  status=$(curl -sS --location "http://localhost:5555/refresh/$JOB_ID")
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
curl -sS --location "http://localhost:5555/data/$JOB_ID" | jq 'to_entries | sort_by(-.value) | from_entries' | head -n 5

# Cleanup
#cleanUp
