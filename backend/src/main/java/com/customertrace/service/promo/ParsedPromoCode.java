package com.customertrace.service.promo;

import java.util.Optional;

/**
 * A parsed promo code.
 *
 * <p>Parsing never throws: it returns an {@link Optional} so callers can distinguish "malformed"
 * from "well-formed but unknown" and report the precise reason to staff.
 */
public record ParsedPromoCode(String letters, int customerTarget, int durationDays, char tier) {

    /** True when the tier letter is in the exclusive E-J range (once per restaurant per month). */
    public boolean isExclusiveTier() {
        return tier >= 'E' && tier <= 'J';
    }

    /** True when the tier is in the reusable A-D range. */
    public boolean isReusableTier() {
        return tier >= 'A' && tier <= 'D';
    }
}