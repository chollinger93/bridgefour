#!/bin/bash

SLEEP_TIME=2
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR/.."

function checkRaftState() {
  hasLeader=false
  for i in {0..2}; do
    raw=$(curl -s --location --request GET "http://localhost:655$i/raft/state")
    st=$(echo "$raw" | jq '.ownState | to_entries | .[].key')
    leader=$(echo "$raw" | jq '.currentLeader')
    term=$(echo "$raw" | jq '.term')
    echo "$(date +%s),$i,$st,$leader,$term"
    if [[ $st == *"Leader"* ]]; then
      hasLeader=true
    fi
  done
  if [[ -n $RUN_FOREVER ]]; then
    sleep $SLEEP_TIME
    checkRaftState
  fi
  if [[ $hasLeader == false ]]; then
    sleep $SLEEP_TIME
    checkRaftState
  fi
}

docker compose up -d
echo "ts,node,state,leader,term"
checkRaftState
#docker compose down
