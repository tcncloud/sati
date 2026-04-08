#!/bin/bash

# Restart wrapper for sati-demo-app
# Handles graceful restarts via USR1 signal and auto-restart on crash

APP_SCRIPT="/home/tcn/app/bin/sati-demo-app"

start_app() {
    echo "Starting sati-demo-app..."
    $APP_SCRIPT &
    APP_PID=$!
    echo "Application started with PID: $APP_PID"
}

handle_restart() {
    echo "Restart signal received"
    RESTART_REQUESTED=1
    if [ ! -z "$APP_PID" ] && kill -0 $APP_PID 2>/dev/null; then
        echo "Stopping application (PID: $APP_PID)..."
        kill -TERM $APP_PID
        # Wait for the process to actually exit so the port is released
        wait $APP_PID 2>/dev/null
        echo "Application stopped"
    fi
}

handle_shutdown() {
    echo "Shutdown signal received"
    if [ ! -z "$APP_PID" ] && kill -0 $APP_PID 2>/dev/null; then
        echo "Stopping application (PID: $APP_PID)..."
        kill -TERM $APP_PID
        wait $APP_PID
    fi
    exit 0
}

trap 'handle_restart' USR1
trap 'handle_shutdown' TERM INT

# Watch for file-based restart trigger
watch_restart_file() {
    local child_pid="$1"
    local parent_pid="$2"
    while kill -0 "$child_pid" 2>/dev/null; do
        if [ -f /tmp/restart-trigger/restart ]; then
            echo "Restart file detected"
            rm -f /tmp/restart-trigger/restart
            kill -USR1 "$parent_pid"
            break
        fi
        sleep 1
    done
}

mkdir -p /tmp/restart-trigger

# Main loop
PARENT_PID=$$
while true; do
    RESTART_REQUESTED=0
    start_app

    watch_restart_file "$APP_PID" "$PARENT_PID" &
    WATCHER_PID=$!

    wait $APP_PID
    EXIT_CODE=$?

    # Cleanup watcher
    kill $WATCHER_PID 2>/dev/null || true
    wait $WATCHER_PID 2>/dev/null || true

    if [ "$RESTART_REQUESTED" = "1" ]; then
        echo "Restarting..."
        continue
    fi

    if [ "$EXIT_CODE" -eq 0 ]; then
        echo "Application exited normally. Stopping."
        exit 0
    fi

    echo "Application exited with code $EXIT_CODE, restarting..."
    sleep 2
done
