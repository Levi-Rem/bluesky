package org.bluesky.training.performance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:performance_seed;MODE=MySQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles({"test", "performance-seed-test"})
class AirbornePerformanceImportTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seed_migration_imports_validated_types_and_height_envelopes() {
        Integer typeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aircraft_performance_type", Integer.class);
        Integer envelopeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aircraft_performance_envelope", Integer.class);
        Double a320ClimbRate = jdbcTemplate.queryForObject(
                "SELECT maximum_vertical_rate_mps FROM aircraft_performance_envelope "
                        + "WHERE aircraft_type='A320' AND flight_phase='CLIMB' "
                        + "AND altitude_meters=6000",
                Double.class);
        Integer outOfEnvelopeNominalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aircraft_performance_envelope "
                        + "WHERE nominal_cas_mps < minimum_cas_mps "
                        + "OR nominal_cas_mps > maximum_cas_mps",
                Integer.class);
        assertThat(typeCount).isEqualTo(9);
        assertThat(envelopeCount).isEqualTo(1188);
        assertThat(a320ClimbRate).isCloseTo(14.6304,
                org.assertj.core.data.Offset.offset(0.000001));
        assertThat(outOfEnvelopeNominalCount).isZero();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE aircraft_performance_envelope "
                        + "SET nominal_cas_mps=maximum_cas_mps+1 "
                        + "WHERE aircraft_type='A320' AND flight_phase='CLIMB' "
                        + "AND altitude_meters=1500"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
