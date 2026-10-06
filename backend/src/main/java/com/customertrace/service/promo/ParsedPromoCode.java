package com.customertrace.service.promo;

import java.util.Optional;

/**
 * A parsed promo code.
 *
 * <p>Parsing never throws: it returns an {@link Optional} so callers can distinguish "malformed"
 * from "well-formed but unknown" and report the precise reason to staff.
 */
public record ParsedPromoCode(String letters, int customerTarget, int durationDays, char tier) {

    /** The discount percentage the tier letter stands for (B=2% … J=10%). */
    public int discountPercent() {
        return DiscountTiers.discountFor(tier);
    }

    /**
     * True when the discount letter is in the restricted G-J range, so it may back at most one
     * campaign per restaurant per calendar month.
     */
    public boolean isExclusiveTier() {
        return DiscountTiers.isRestricted(tier);
    }

    /** True when the discount letter is in the reusable B-F range. */
    public boolean isReusableTier() {
        return DiscountTiers.isReusable(tier);
    }
}