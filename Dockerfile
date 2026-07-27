# Containerizes the existing working server (ServerMain -> MatchmakingServer)
# unchanged - today's goal is a small *working* Docker Compose setup, not a
# rewrite into the multi-service design described in Server_Design.md.
FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY . .

RUN rm -rf out && mkdir out \
    && find . -name "*.java" > /tmp/sources.txt \
    && javac -cp "lib/*" -d out @/tmp/sources.txt

EXPOSE 5000
VOLUME ["/data"]

# users.db lives on the mounted /data volume so accounts/ELO survive restarts.
ENTRYPOINT ["java", "-cp", "out:lib/slf4j-api.jar:lib/slf4j-nop.jar:lib/sqlite-jdbc.jar", "ServerMain", "5000", "/data/users.db"]
