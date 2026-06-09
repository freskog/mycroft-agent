# syntax=docker/dockerfile:1

# ---------- base: GraalVM (with native-image + C toolchain) + sbt ----------
# native-image-community ships GraalVM as the JDK plus the gcc/glibc/zlib
# toolchain that `native-image` needs to link a real binary.
FROM ghcr.io/graalvm/native-image-community:21-ol9 AS base

# native-image-community uses `native-image` as its entrypoint; reset it so we
# can run sbt and arbitrary commands.
ENTRYPOINT []

# Tell sbt-native-image to use the GraalVM already present in this image
# (JAVA_HOME points at GraalVM) instead of downloading one via coursier.
ENV NATIVE_IMAGE_INSTALLED=true
ENV GRAALVM_HOME=$JAVA_HOME

# Tools sbt's launcher and dependency fetch need on top of the slim OL9 base.
RUN microdnf install -y tar gzip util-linux which findutils && microdnf clean all

# Install sbt
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v1.10.9/sbt-1.10.9.tgz" | tar xz -C /opt && \
    ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /workspace

# Pre-fetch dependencies (cached unless the build definition changes)
COPY project/build.properties project/build.properties
COPY project/plugins.sbt project/plugins.sbt
RUN mkdir -p src/main/scala && \
    echo 'ThisBuild / scalaVersion := "2.13.18"' > build.sbt && \
    sbt update && \
    rm -rf src build.sbt

CMD ["sbt"]

# ---------- builder: compile the GraalVM native binaries ----------
FROM base AS builder

COPY . .

RUN sbt \
      "safeRun/nativeImage" \
      "runlog/nativeImage" \
      "personCli/nativeImage" \
      "personService/nativeImage" \
      "runtime/nativeImage" \
      "mycroft/nativeImage"

# Collect the produced binaries into a single directory.
RUN mkdir -p /out && \
    cp modules/safe-run/target/native-image/safe-run         /out/ && \
    cp modules/runlog/target/native-image/runlog             /out/ && \
    cp modules/person-cli/target/native-image/person-cli     /out/ && \
    cp modules/person-service/target/native-image/person-service /out/ && \
    cp modules/runtime/target/native-image/runtime           /out/ && \
    cp modules/mycroft/target/native-image/mycroft           /out/

# ---------- runtime: minimal image shipping only the binaries ----------
# GraalVM links glibc, and OL9's glibc (2.34) is forward-compatible with the
# newer glibc in debian:12-slim, so the binaries run here without a JVM.
FROM debian:12-slim AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    util-linux \
    bash \
    python3 \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s /usr/bin/python3 /usr/local/bin/python

COPY --from=builder /out/ /usr/local/bin/

# Skill docs and the agent-protocol skill reference the dossier names
# `person` and `skill`. The native binaries are named after their modules
# (`person-cli`, `runtime`). Symlink so both spellings work and the agent
# does not waste turns rediscovering the right path.
RUN ln -s /usr/local/bin/person-cli /usr/local/bin/person && \
    ln -s /usr/local/bin/runtime    /usr/local/bin/skill

WORKDIR /workspace

CMD ["bash"]

# ---------- jarbuilder: assemble the JVM REPL fat jar ----------
# Separate from the native `builder` stage so iterating on the REPL only runs
# `sbt assembly` (seconds) instead of the multi-minute native-image link.
FROM base AS jarbuilder

COPY . .

RUN sbt "mycroftRepl/assembly" && \
    mkdir -p /out && \
    cp modules/mycroft-repl/target/scala-2.13/mycroft-repl.jar /out/

# ---------- repl: tiny JRE image running the interactive REPL ----------
# The REPL only speaks HTTP to mycroft, so it needs no native tool binaries —
# just a JVM and JLine (bundled in the fat jar) for the terminal UI.
FROM eclipse-temurin:21-jre AS repl

COPY --from=jarbuilder /out/mycroft-repl.jar /app/mycroft-repl.jar

WORKDIR /workspace

ENTRYPOINT ["java", "-jar", "/app/mycroft-repl.jar"]
