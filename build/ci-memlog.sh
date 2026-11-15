#!/usr/bin/env bash
# Background memory/disk logger for CI runners: prints one line every 15s so a killed runner
# leaves a trace of what was using memory. Started by release.yml, runs until the job ends.
while true; do
  mem=$(free -m | awk '/Mem:/{print "used=" $3 " avail=" $7}')
  swap=$(free -m | awk '/Swap:/{print $3}')
  disk=$(df -m --output=avail / | tail -1 | tr -d ' ')
  top=$(ps -eo rss,comm --sort=-rss | sed -n 2p)
  echo "[mem-log $(date -u +%T)] $mem swap=$swap disk_free_mb=$disk top=$top"
  sleep 15
done
