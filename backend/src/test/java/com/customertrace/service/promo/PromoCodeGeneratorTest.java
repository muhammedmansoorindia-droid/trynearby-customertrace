package com.customertrace.service.promo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the promo-code rules.
 *
 * <p>Pure unit tests: no database and no Spring context, so they run in milliseconds and cannot
 * be affected by the developer's data.
 */
class PromoCodeGeneratorTest {

    /** The generator only needs to know which full codes already exist. */
    private PromoCodeGenerator generator(Set<String> takenCodes) {
        return new PromoCodeGenerator().withCodeExistsChecker(takenCodes::contains);
    }

    private PromoCodeGenerator plain() {
        return generator(Set.of());
    }

    @Nested
    @DisplayName("format LLNNDDX")
    class Format {

        @Test
        @DisplayName("generates a code matching the required shape")
        void generatesWellFormedCode() {
            String code = plain().generate(50, 7, 5);

            assertThat(code).matches(PromoCodeGenerator.PROMO_CODE_PATTERN);
            assertThat(code).hasSize(7);
        }

        @Test
        @DisplayName("encodes the target in NN and the duration in DD")
        void encodesTargetAndDuration() {
            String code = plain().generate(42, 17, 5);
            ParsedPromoCode parsed = plain().parse(code).orElseThrow();

            assertThat(parsed.customerTarget()).isEqualTo(42);
            assertThat(parsed.durationDays()).isEqualTo(17);
        }

        @Test
        @DisplayName("zero-pads single-digit values so the code stays 7 characters")
        void padsValues() {
            String code = plain().generate(9, 7, 5);

            assertThat(code).hasSize(7);
            assertThat(code.substring(2, 4)).isEqualTo("09");
            assertThat(code.substring(4, 6)).isEqualTo("07");
        }

        @Test
        @DisplayName("always emits a discount letter in B-J")
        void tierAlwaysInRange() {
            for (int i = 0; i < 200; i++) {
                char tier = plain().generate(1 + (i % 99), 1 + (i % 99), 2 + (i % 9)).charAt(6);
                assertThat(tier).isBetween('B', 'J');
            }
        }

        @Test
        @DisplayName("parses valid codes and rejects malformed ones")
        void parsesAndRejects() {
            PromoCodeGenerator g = plain();

            assertThat(g.parse("AB1203B")).isPresent();
            assertThat(g.parse("ab1203b")).isPresent(); // case-insensitive
            assertThat(g.parse("AB1203K")).isEmpty(); // discount letter beyond J
            assertThat(g.parse("AB1203A")).isEmpty(); // A is below the 2% floor
            assertThat(g.parse("AB120")).isEmpty(); // too short
            assertThat(g.parse("AB1203BB")).isEmpty(); // too long
            assertThat(g.parse("12003B")).isEmpty(); // digits where letters belong
            assertThat(g.parse("AB0003B")).isEmpty(); // target below the minimum
            assertThat(g.parse("AB9999J")).isPresent(); // 99 target / 99 days, both representable
            assertThat(g.parse("AB9900J")).isEmpty(); // duration of zero days
        }
    }

    @Nested
    @DisplayName("rejects values that cannot fit two digits")
    class Encodability {

        @Test
        @DisplayName("rejects a customer target of 150 rather than truncating it")
        void rejectsLargeTarget() {
            // 150 cannot be represented as NN; silently truncating to 15 would be a data bug.
            assertThatThrownBy(() -> plain().generate(150, 7, 5))
                    .isInstanceOf(PromoCodeValidationException.class)
                    .hasMessageContaining("Customer target must be between 1 and 99");
        }

        @Test
        @DisplayName("rejects a target of zero, which is not a campaign")
        void rejectsZeroTarget() {
            assertThatThrownBy(() -> plain().generate(0, 7, 5))
                    .isInstanceOf(PromoCodeValidationException.class)
                    .hasMessageContaining("Customer target");
        }

        @Test
        @DisplayName("rejects a duration outside 1-99")
        void rejectsInvalidDuration() {
            assertThatThrownBy(() -> plain().generate(50, 100, 5))
                    .isInstanceOf(PromoCodeValidationException.class)
                    .hasMessageContaining("Campaign duration must be between 1 and 99");
            // A campaign that lasts no days is not a campaign.
            assertThatThrownBy(() -> plain().generate(50, 0, 5))
                    .isInstanceOf(PromoCodeValidationException.class)
                    .hasMessageContaining("Campaign duration");
        }

        @Test
        @DisplayName("accepts the boundary values")
        void acceptsBoundaries() {
            assertThat(plain().generate(1, 1, 2)).matches(PromoCodeGenerator.PROMO_CODE_PATTERN);
            assertThat(plain().generate(99, 99, 10)).matches(PromoCodeGenerator.PROMO_CODE_PATTERN);
        }

        @Test
        @DisplayName("rejects a discount that no single letter can express")
        void rejectsUnmappableDiscount() {
            assertThatThrownBy(() -> plain().generate(50, 7, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Discount must be between 2% and 10%");
            assertThatThrownBy(() -> plain().generate(50, 7, 11))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Discount must be between 2% and 10%");
        }
    }

    @Nested
    @DisplayName("discount letters B-J")
    class DiscountMapping {

        @Test
        @DisplayName("maps every percentage to exactly the letter the spec requires")
        void mapsEveryDiscount() {
            // The whole table, asserted literally so a shifted mapping cannot pass unnoticed.
            assertThat(DiscountTiers.letterFor(2)).isEqualTo('B');
            assertThat(DiscountTiers.letterFor(3)).isEqualTo('C');
            assertThat(DiscountTiers.letterFor(4)).isEqualTo('D');
            assertThat(DiscountTiers.letterFor(5)).isEqualTo('E');
            assertThat(DiscountTiers.letterFor(6)).isEqualTo('F');
            assertThat(DiscountTiers.letterFor(7)).isEqualTo('G');
            assertThat(DiscountTiers.letterFor(8)).isEqualTo('H');
            assertThat(DiscountTiers.letterFor(9)).isEqualTo('I');
            assertThat(DiscountTiers.letterFor(10)).isEqualTo('J');
        }

        @Test
        @DisplayName("reads the same percentage back out of each letter")
        void mapsEveryLetter() {
            for (int pct = 2; pct <= 10; pct++) {
                assertThat(DiscountTiers.discountFor(DiscountTiers.letterFor(pct)))
                        .as("round trip for %d%%", pct)
                        .isEqualTo(pct);
            }
        }

        @Test
        @DisplayName("generates a code ending in the letter the discount demands")
        void codeEndsWithTheDiscountLetter() {
            // The rule that matters commercially: a 5% campaign must never produce an F or a G.
            for (int pct = 2; pct <= 10; pct++) {
                String code = plain().generate(50, 7, pct);
                assertThat(code.charAt(6))
                        .as("discount %d%%", pct)
                        .isEqualTo(DiscountTiers.letterFor(pct));
                assertThat(plain().parse(code).orElseThrow().discountPercent())
                        .isEqualTo(pct);
            }
        }

        @Test
        @DisplayName("treats B-F as reusable and G-J as restricted")
        void classifiesRestrictedLetters() {
            PromoCodeGenerator g = plain();

            for (char letter = 'B'; letter <= 'F'; letter++) {
                assertThat(g.isReusableDiscount(letter)).as("%c reusable", letter).isTrue();
                assertThat(g.isRestrictedDiscount(letter)).as("%c restricted", letter).isFalse();
            }
            for (char letter = 'G'; letter <= 'J'; letter++) {
                assertThat(g.isRestrictedDiscount(letter)).as("%c restricted", letter).isTrue();
                assertThat(g.isReusableDiscount(letter)).as("%c reusable", letter).isFalse();
            }
        }

        @Test
        @DisplayName("classifies reusable and restricted on a parsed code too")
        void classifiesParsedTiers() {
            PromoCodeGenerator g = plain();

            assertThat(g.parse("AB1203B").orElseThrow().isExclusiveTier()).isFalse();
            assertThat(g.parse("AB1203F").orElseThrow().isExclusiveTier()).isFalse();
            assertThat(g.parse("AB1203G").orElseThrow().isExclusiveTier()).isTrue();
            assertThat(g.parse("AB1203J").orElseThrow().isExclusiveTier()).isTrue();
            assertThat(g.parse("AB1203B").orElseThrow().isReusableTier()).isTrue();
            assertThat(g.parse("AB1203J").orElseThrow().isReusableTier()).isFalse();
        }
    }

    @Nested
    @DisplayName("uniqueness")
    class Uniqueness {

        @Test
        @DisplayName("gives up rather than returning a duplicate when every candidate is taken")
        void retriesUntilUnique() {
            // Every candidate code is reported as taken, so the generator must exhaust its
            // attempt budget instead of handing back an existing code.
            PromoCodeGenerator allTaken = new PromoCodeGenerator()
                    .withCodeExistsChecker(code -> true);

            assertThatThrownBy(() -> allTaken.generate(50, 7, 5))
                    .isInstanceOf(PromoCodeValidationException.class)
                    .hasMessageContaining("unique");
        }

        @Test
        @DisplayName("does not return a code that already exists")
        void skipsTakenCodes() {
            String first = plain().generate(50, 7, 5);
            String second = generator(Set.of(first)).generate(50, 7, 5);

            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("re-drawing only changes the letters, never the requested values")
        void collisionKeepsTheRequestedValues() {
            // When the first candidate is taken, the retry must keep NN, DD and the discount
            // letter the caller asked for: silently changing the target or the discount to fit a
            // free code would mis-sell the offer.
            Set<String> taken = new java.util.HashSet<>();
            for (int i = 0; i < 200; i++) {
                String code = generator(taken).generate(37, 12, 6);
                taken.add(code);
                assertThat(code.substring(2, 4)).isEqualTo("37");
                assertThat(code.substring(4, 6)).isEqualTo("12");
                assertThat(code.charAt(6)).isEqualTo('F'); // 6%
            }
            assertThat(taken).as("every code is distinct").hasSize(200);
        }
    }
}