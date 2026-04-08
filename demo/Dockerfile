# Dockerfile for sati-demo-app
# Build first with: ./gradlew installDist

FROM eclipse-temurin:21-jre

# Setup directories and user
RUN mkdir -p /workdir/config \
    && useradd -ms /bin/bash tcn \
    && chown -R tcn:tcn /workdir/config

# Install minimal debugging tools
RUN apt update -y && apt install -y curl && apt clean && rm -rf /var/lib/apt/lists/*

WORKDIR /home/tcn

# Copy restart wrapper
COPY restart-wrapper.sh /home/tcn/restart-wrapper.sh
RUN chmod +x /home/tcn/restart-wrapper.sh && chown tcn:tcn /home/tcn/restart-wrapper.sh

USER tcn

# Copy the pre-built distribution (from ./gradlew installDist)
COPY build/install/sati-demo-app/ /home/tcn/app/

EXPOSE 8080

# JVM options as environment variable (easy to override)
ENV JAVA_OPTS="-Xmx4g -Xms1g -Denv=prod"

# Run the app directly (restart-wrapper calls this)
ENTRYPOINT ["/home/tcn/restart-wrapper.sh"]
