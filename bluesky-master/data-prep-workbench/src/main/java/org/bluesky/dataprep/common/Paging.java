package org.bluesky.dataprep.common;

/** Centralized page normalization that prevents page * size integer overflow. */
public final class Paging {

    private Paging() {
    }

    public static int safePage(int page, int safeSize) {
        return Math.min(Math.max(page, 0), Integer.MAX_VALUE / Math.max(safeSize, 1));
    }
}
