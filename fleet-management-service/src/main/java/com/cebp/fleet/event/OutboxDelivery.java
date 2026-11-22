package com.cebp.fleet.event;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "fleet.kafka.enabled", havingValue = "true")
class OutboxDelivery {
    private static final Logger log = LoggerFactory.getLogger(OutboxDelivery.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;

    OutboxDelivery(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${fleet.kafka.outbox-delay-ms:500}")
    void drain() {
        try {
            for (int i = 0; i < 25; i++) {
                if (!Boolean.TRUE.equals(transactions.execute(status -> deliverNext()))) return;
            }
        } catch (RuntimeException e) {
            log.warn("Fleet outbox publish failed. Oldest pending age (seconds): {}. Will retry.",
                    jdbc.queryForObject("""
                            select coalesce(extract(epoch from (now() - min(queued_at))), 0)::bigint
                            from fleet_outbox where delivered_at is null
                            """, Long.class), e);
        }
    }

    private boolean deliverNext() {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(712309013)", Boolean.class))) {
            return false;
        }
        List<Entry> pending = jdbc.query("""
                select event_id, topic, vehicle_id, body::text from fleet_outbox
                where delivered_at is null order by state_revision limit 1
                """, (rs, row) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        if (pending.isEmpty()) return false;
        Entry event = pending.get(0);
        try {
            kafka.send(event.topic(), event.vehicleId(), event.body()).get(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing " + event.id(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka did not acknowledge " + event.id(), e);
        }
        jdbc.update("update fleet_outbox set delivered_at = now() where event_id = ?", event.id());
        return true;
    }

    private record Entry(String id, String topic, String vehicleId, String body) {}
}
