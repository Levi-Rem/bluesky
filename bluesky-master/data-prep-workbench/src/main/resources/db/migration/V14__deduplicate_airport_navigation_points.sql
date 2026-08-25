-- Airports are authoritative in the airport table.  Older ACCOPS imports also
-- materialized airports as navigation points, which violates the runtime
-- catalogue's global code uniqueness rule.  Keep the imported rows for audit
-- history, but exclude them from the active runtime catalogue.
UPDATE navigation_point
SET status = 'DISABLED',
    revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 'runtime-nav-dedup'
WHERE deleted = FALSE
  AND status = 'ENABLED'
  AND UPPER(TRIM(point_type)) IN ('AIRPORT', 'AIRPORT_I')
  AND EXISTS (
      SELECT 1
      FROM airport
      WHERE airport.deleted = FALSE
        AND airport.status = 'ENABLED'
        AND UPPER(TRIM(airport.code)) = UPPER(TRIM(navigation_point.code))
  );
