#!/bin/bash

set -eou pipefail
trap cleanUp INT

function createData() {
  rm -rf /tmp/data/in
  rm -rf /tmp/data/out
  mkdir -p /tmp/data/in
  mkdir -p /tmp/data/out
  if [[ ! -f "/tmp/war_and_peace.txt" ]]; then
    wget https://www.gutenberg.org/cache/epub/2600/pg2600.txt -O /tmp/war_and_peace.txt
  fi
  # 66030 lines => 4 workers => ~16500 lines per worker
  split -l 16500 /tmp/war_and_peace.txt /tmp/data/in/war_and_peace.txt.
}

function startCluster(){
  sbt docker:publishLocal
  docker-compose up -d
  echo "Sleeping for 10 seconds to let the cluster start"
  sleep 10
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
LEADER_PORT=6550
WORKER1_PORT=6551
WORKER2_PORT=6552
