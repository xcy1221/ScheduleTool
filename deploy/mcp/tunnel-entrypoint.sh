#!/bin/bash
set -eu

if [[ "${1:-}" == run ]]; then
    trap 'exit 143' TERM INT
    echo 'Waiting for ScheduleTool backend.'
    ready=false
    for ((attempt=0; attempt<60; attempt++)); do
        if (
            exec 3<>/dev/tcp/scheduletool/8080
            printf 'GET /schedule/ HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
            IFS= read -r -t 3 status <&3
            [[ "$status" == HTTP/*" 200"* ]]
        ) 2>/dev/null; then
            ready=true
            break
        fi
        /bin/sleep 2
    done
    if [[ "$ready" != true ]]; then
        echo 'ScheduleTool backend did not become ready.' >&2
        exit 1
    fi
    echo 'ScheduleTool backend ready.'
fi

exec /usr/local/bin/tunnel-client "$@"
