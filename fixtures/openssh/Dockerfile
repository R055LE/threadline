FROM alpine:3.22

RUN apk add --no-cache \
    bash \
    coreutils \
    less \
    openssh-client \
    openssh-server \
    procps \
    shadow \
    sshpass \
    vim \
    zsh \
    && adduser -D -h /home/threadline -s /bin/bash threadline

COPY sshd_config /etc/ssh/sshd_config
COPY entrypoint.sh /usr/local/bin/threadline-entrypoint
COPY test-output.sh /usr/local/bin/threadline-test-output

RUN chmod 0755 \
    /usr/local/bin/threadline-entrypoint \
    /usr/local/bin/threadline-test-output

EXPOSE 22

ENTRYPOINT ["/usr/local/bin/threadline-entrypoint"]
