package org.bluesky.training.performance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AirbornePerformanceSchemaTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flyway_creates_normalized_airborne_performance_tables() {
        Integer typeTable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_name='aircraft_performance_type'",
                Integer.class);
        Integer envelopeTable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_name='aircraft_performance_envelope'",
                Integer.class);

        assertThat(typeTable).isEqualTo(1);
        assertThat(envelopeTable).isEqualTo(1);
    }
}
