#!/bin/bash

set -eo pipefail
trap cleanUp INT

JOB_CLASS="com.chollinger.bridgefour.jobs.example.DelayedWordCountBridgeFourJob"
sleep=10
LEADER_PORT=6550
WORKER1_PORT=6551
WORKER2_PORT=6552

function uploadJar() {
  if [ ! -z "$JAR_PATH" ]; then
    echo "Jar path already set to $JAR_PATH"
  else
    read -p "Enter jar path: " JAR_PATH
  fi
  if [ ! -f "$JAR_PATH" ]; then
    echo "File does not exist."
    exit 1
  fi
  jar=$(tar -tf "$JAR_PATH" | grep "$JOB_CLASS")
  if [ -z "$jar" ]; then
    echo "Jar does not contain $JOB_CLASS"
    exit 1
  fi
  mkdir -p /tmp/jars
  cp "${JAR_PATH}" /tmp/jars/
}

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

function startCluster() {
  if [ -n "$BUILD" ]; then
    echo "Building docker images"
    sbt docker:publishLocal
  fi
  docker compose up -d
  echo "Sleeping for 10 seconds to let the cluster start"
  sleep 10
}

function startJob() {
  start=$(curl -sS --location "http://localhost:$LEADER_PORT/start" \
    --header 'Content-Type: application/json' \
    --data "{
          \"name\": \"Example job\",
          \"jobClass\": \"$JOB_CLASS\",
          \"input\": \"/data/in\",
          \"output\": \"/data/out\",
          \"userSettings\": {\"timeout\": \"2\"}
      }")
  JOB_ID=$(echo "$start" | jq '.jobId')
  echo "${start}"
}

function cleanUp() {
  echo "Cleaning up"
  docker compose down
}

function printWorkerState() {
  local port=$1
  echo $(curl -Ss --location "http://localhost:$port/worker/state")
}
