package com.customertrace.service.promo;

/**
 * The discount letter that closes an {@code LLNNDDX} code, and the percentage it stands for.
 *
 * <p>The letter is a <em>discount</em> code, not an arbitrary tier: the creator picks a discount
 * and the letter follows from it, so a code can never advertise one discount and charge another.
 *
 * <pre>
 *   B=2%  C=3%  D=4%  E=5%  F=6%  G=7%  H=8%  I=9%  J=10%
 * </pre>
 *
 * <p>B-F are reusable without a monthly cap. G-J are scarce: each may back at most one campaign
 * per restaurant per calendar month, so a restaurant cannot offer the same high discount twice in
 * a month no matter how many creators are assigned to it.
 */
public final class DiscountTiers {

    /** Lowest and highest discount a code can express. */
    public static final int MIN_DISCOUNT_PERCENT = 2;
    public static final int MAX_DISCOUNT_PERCENT = 10;

    /** First letter, B, which is 2%. */
    public static final char FIRST_LETTER = 'B';
    /** Last letter, J, which is 10%. */
    public static final char LAST_LETTER = 'J';

    private DiscountTiers() {
    }

    /**
     * The letter for a discount percentage.
     *
     * @throws IllegalArgumentException when the percentage is outside 2-10, which is the range the
     *                                  single letter can express
     */
    public static char letterFor(int discountPercent) {
        if (discountPercent < MIN_DISCOUNT_PERCENT || discountPercent > MAX_DISCOUNT_PERCENT) {
            throw new IllegalArgumentException(
                    "Discount must be between " + MIN_DISCOUNT_PERCENT + "% and "
                            + MAX_DISCOUNT_PERCENT + "% because it is encoded as the code's "
                            + "discount letter B-J. Received: " + discountPercent + ".");
        }
        return (char) (FIRST_LETTER + discountPercent - MIN_DISCOUNT_PERCENT);
    }

    /**
     * The discount a letter stands for.
     *
     * @throws IllegalArgumentException when the letter is outside B-J
     */
    public static int discountFor(char letter) {
        if (letter < FIRST_LETTER || letter > LAST_LETTER) {
            throw new IllegalArgumentException(
                    "Discount letter must be between " + FIRST_LETTER + " and " + LAST_LETTER
                            + ". Received: " + letter + ".");
        }
        return letter - FIRST_LETTER + MIN_DISCOUNT_PERCENT;
    }

    /** True when the letter may back more than one campaign per restaurant per month. */
    public static boolean isReusable(char letter) {
        return letter >= FIRST_LETTER && letter <= 'F';
    }

    /**
     * True when the letter is limited to one campaign per restaurant per calendar month.
     *
     * <p>G-J (7%-10%).
     */
    public static boolean isRestricted(char letter) {
        return letter >= 'G' && letter <= LAST_LETTER;
    }
}