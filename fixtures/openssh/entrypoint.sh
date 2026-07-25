#!/bin/sh
set -eu

if [ -z "${THREADLINE_TEST_PASSWORD:-}" ]; then
    echo "THREADLINE_TEST_PASSWORD must be set" >&2
    exit 64
fi

printf '%s:%s\n' threadline "$THREADLINE_TEST_PASSWORD" | chpasswd

install -d -m 0700 -o threadline -g threadline /home/threadline/.ssh
install -m 0600 -o threadline -g threadline \
    /fixture-keys/client_ed25519.pub \
    /home/threadline/.ssh/authorized_keys

install -d -m 0700 /var/lib/threadline-ssh
if [ ! -f /var/lib/threadline-ssh/ssh_host_ed25519_key ]; then
    ssh-keygen -q -t ed25519 -N '' \
        -f /var/lib/threadline-ssh/ssh_host_ed25519_key
fi
if [ ! -f /var/lib/threadline-ssh/ssh_host_rsa_key ]; then
    ssh-keygen -q -t rsa -b 3072 -N '' \
        -f /var/lib/threadline-ssh/ssh_host_rsa_key
fi

exec /usr/sbin/sshd -D -e -f /etc/ssh/sshd_config
