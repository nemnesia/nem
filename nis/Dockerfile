FROM eclipse-temurin:25-jdk-jammy AS builder

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# install dependencies (install tzdata first to prevent 'geographic area' prompt)
RUN apt-get update \
  && apt-get install -y tzdata git libssl-dev maven ca-certificates \
  && update-ca-certificates \
  && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /build/nem

# copy local code into /build/nem
COPY . /build/nem

WORKDIR /build/nem

# actually build with the Java 25 primary runtime baseline
RUN java -version \
  && mvn -version \
  && mvn clean package -DskipTests=true \
  && cd infra \
  && bash ./package.prepare.sh package

# second stage of multistage build
FROM eclipse-temurin:25-jre-jammy AS runner

ENV MEMORY_MS=6G
ENV MEMORY_MX=6G

ARG UID=11001
ARG UNAME=nem

RUN useradd --uid $UID -ms /bin/bash $UNAME

VOLUME ["/usersettings"]

WORKDIR /app

COPY --from=builder /build/nem/infra/package /app

RUN chown -R ${UNAME} /app

USER $UNAME

CMD ["bash", "-c", "java -Xms${MEMORY_MS} -Xmx${MEMORY_MX} -cp /usersettings:./nis/*:./libs/* org.nem.deploy.CommonStarter"]
