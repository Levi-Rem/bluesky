package org.bluesky.training.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal immutable runway resource for instruction integration tests. */
public final class NavigationFixture {
    private NavigationFixture() {}
    public static void installRunways(JdbcTemplate jdbc,String group) {
        try {
            Path dir=Files.createTempDirectory("bluesky-runway-test-");
            String content="[{\"airportCode\":\"ZGGG\",\"runway\":\"02R\",\"thresholdLatitude\":23.3,\"thresholdLongitude\":113.3,\"elevationFt\":15,\"trueHeadingDeg\":20,\"ilsAvailable\":true},{\"airportCode\":\"ZGGG\",\"runway\":\"02L\",\"thresholdLatitude\":23.3,\"thresholdLongitude\":113.3,\"elevationFt\":15,\"trueHeadingDeg\":20,\"ilsAvailable\":true}]";
            Files.write(dir.resolve("runways.json"),content.getBytes(StandardCharsets.UTF_8));
            String manifest="{\"resources\":{\"runways\":{\"file\":\"runways.json\"}}}";
            Files.write(dir.resolve("manifest.json"),manifest.getBytes(StandardCharsets.UTF_8));
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO reference_snapshot(id,version_label,status,schema_version,manifest_json,manifest_checksum,store_path,published_at) VALUES (?,?,'PUBLISHED','reference-manifest/1',?,?,?,CURRENT_TIMESTAMP)",id,"instruction-test",manifest,org.bluesky.training.reference.ReferenceSnapshotStore.sha256OfText(manifest),dir.toString());
            jdbc.update("UPDATE exercise_group SET reference_snapshot_id=? WHERE id=?",id,group);
            dir.toFile().deleteOnExit();dir.resolve("manifest.json").toFile().deleteOnExit();dir.resolve("runways.json").toFile().deleteOnExit();
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
}
