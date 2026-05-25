FROM eclipse-temurin:21-jdk

RUN apt-get update && apt-get install -y \
    util-linux \
    curl \
    bash \
    && rm -rf /var/lib/apt/lists/*

# Install sbt
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v1.10.9/sbt-1.10.9.tgz" | tar xz -C /opt && \
    ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /workspace

# Pre-fetch sbt launcher
COPY project/build.properties project/build.properties
COPY project/plugins.sbt project/plugins.sbt
RUN mkdir -p src/main/scala && \
    echo 'ThisBuild / scalaVersion := "2.13.16"' > build.sbt && \
    sbt update && \
    rm -rf src build.sbt

COPY . .

CMD ["sbt"]
