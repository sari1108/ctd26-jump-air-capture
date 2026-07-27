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
EXPOSE 8080
VOLUME ["/data"]

# DB_URL, when set (docker-compose sets it to the postgres container), switches
# UserDatabase over to PostgreSQL. Unset, it falls back to a SQLite file on the
# mounted /data volume - so `docker run` without compose still works standalone.
# REDIS_URL ("host:port"), when set, moves the matchmaking queue/room registry onto
# Redis (RedisClient is hand-rolled, no extra jar needed on the classpath for it).
ENTRYPOINT ["/bin/sh", "-c", "java -cp \"out:lib/slf4j-api.jar:lib/slf4j-nop.jar:lib/sqlite-jdbc.jar:lib/postgresql.jar\" ServerMain 5000 \"${DB_URL:-/data/users.db}\""]
