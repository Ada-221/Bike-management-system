# Week 2 Telemetry Demo

Open Docker Desktop and run these commands from the project root.

1. Start Kafka.

   ```bash
   docker compose up -d --wait kafka
   ```

2. Start the telemetry service with the simulator enabled.

   ```bash
   docker run --rm -it \
     --network bike-management-system_default \
     -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
     -e TELEMETRY_SIMULATOR_ENABLED=true \
     -v "$PWD/telemetry-ingestion-service:/app" \
     -w /app \
     maven:3-eclipse-temurin-17 mvn spring-boot:run
   ```

3. Once you see two `Published measurement` lines, open another terminal
   in the project root and read the Kafka messages.

   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
     --bootstrap-server kafka:9092 \
     --topic fleet.vehicle --from-beginning \
     --property print.key=true --max-messages 4 --timeout-ms 10000
   ```

On a fresh topic, expect two battery events (75% and 74%) and two location
events, all with the key `veh_001`. On an existing topic, `--from-beginning`
can show records from earlier runs.

The local Kafka flow was verified. Fleet backend integration is pending.