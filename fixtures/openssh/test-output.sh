#!/bin/sh
set -eu

case "${1:-all}" in
    ansi)
        printf '\033[31mred\033[0m\n'
        ;;
    progress)
        for step in 1 2 3; do
            printf '\rstep %s' "$step"
            sleep 1
        done
        printf '\n'
        ;;
    unicode)
        printf 'unicode: π 日本語 🚀\n'
        ;;
    volume)
        yes line | head -n 100000
        ;;
    all)
        printf 'stdout\n'
        printf 'stderr\n' >&2
        printf '\033[31mred\033[0m\n'
        printf 'unicode: π 日本語 🚀\n'
        printf 'without newline'
        ;;
    *)
        echo "usage: threadline-test-output [all|ansi|progress|unicode|volume]" >&2
        exit 64
        ;;
esac
