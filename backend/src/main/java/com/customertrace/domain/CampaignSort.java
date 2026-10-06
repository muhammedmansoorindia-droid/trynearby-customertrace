package com.customertrace.domain;

/**
 * Order in which a campaign list is returned.
 *
 * <p>Named rather than accepting a raw sort field, so a caller cannot order by an arbitrary column
 * or smuggle a property name into the query. Each option names the column it orders by explicitly;
 * a new order is a new constant here, never a caller-supplied field.
 */
public enum CampaignSort {
    /** Most recently created first — the order a review queue is read in. */
    NEWEST,
    /** Oldest first, for tracing a campaign back to when it was started. */
    OLDEST,
    /**
     * Most recently *touched* first, by the row's own {@code updated_at}.
     *
     * <p>Answers "what changed since I last looked", which {@link #NEWEST} cannot: a campaign created
     * weeks ago but decided this morning is the one worth reviewing.
     */
    UPDATED,
    /**
     * Largest target first.
     *
     * <p>Puts the biggest campaigns at the top, so a reviewer meets the highest-stakes ones first.
     */
    TARGET
}