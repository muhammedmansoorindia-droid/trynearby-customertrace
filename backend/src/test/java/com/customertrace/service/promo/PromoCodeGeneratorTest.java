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

    private PromoCodeGenerator generator(Set<Character> spentTiers, Set<String> takenCodes) {
        return new PromoCodeGenerator()
                .withExclusiveTierTakenChecker(spentTiers::contains)
                .withCodeExistsChecker(takenCodes::contains);
    }

    private PromoCodeGenerator plain() {
        return generator(Set.of(), Set.of());
    }

    @Nested
    @DisplayName("format LLNNDDX")
    class Format {

        @Test
        @DisplayName("generates a code matching the required shape")
        void generatesWellFormedCode() {
            String code = plain().generate(50, 7);

            assertThat(code).matches(PromoCodeGenerator.PROMO_CODE_PATTERN);
            assertThat(code).hasSize(7);
        }

        @Test
        @DisplayName("encodes the target in NN and the duration in DD")
        void encodesTargetAndDuration() {
            String code = plain().generate(42, 17);
            ParsedPromoCode parsed = plain().parse(code).orElseThrow();

            assertThat(parsed.customerTarget()).isEqualTo(42);
            assertThat(parsed.durationDays()).isEqualTo(17);
        }

        @Test
        @DisplayName("zero-pads single-digit durations so the code stays 7 characters")
        void padsDuration() {
            String code = plain().generate(30, 7);

            assertThat(code).hasSize(7);
            assertThat(code.substring(4, 6)).isEqualTo("07");
        }

        @Test
        @DisplayName("always emits a tier letter in A-J")
        void tierAlwaysInRange() {
            for (int i = 0; i < 200; i++) {
                char tier = plain().generate(20 + (i % 70), 1 + (i % 28)).charAt(6);
                assertThat(tier).isBetween('A', 'J');
            }
        }

        @Test
        @DisplayName("parses valid codes and rejects malformed ones")
        void parsesAndRejects() {
            PromoCodeGenerator g = plain();

            assertThat(g.parse("AB1203A")).isPresent();
            assertThat(g.parse("ab1203a")).isPresent(); // case-insensitive
            assertThat(g.parse("AB1203K")).isEmpty(); // tier beyond J
            assertThat(g.parse("AB120")).isEmpty(); // too short
            assertThat(g.parse("AB1203AB")).isEmpty(); // too long
            assertThat(g.parse("12003A")).isEmpty(); // digits where letters belong
            assertThat(g.parse("AB0003A")).isEmpty(); // target below the minimum
            assertThat(g.parse("AB9931A")).isPresent(); // 99 target / 31 days, both representable
            assertThat(g.parse("AB9932A")).isEmpty(); // duration above 31
        }
@Nested
    @DisplayName("rejects values that cannot fit two digits")
    class Encodability {

        @Test
        @DisplayName("rejects a customer target of 150 rather than truncating it")
        void rejectsLargeTarget() {
            // 150 cannot be represented as NN; silently truncating to 15 would be a data bug.
            assertThatThrownBy(() -> plain().generate(150, 7))
                    .isInstanceOf(PromoCodeValidationException.class)
                    .hasMessageContaining("Customer target must be between 10 and 99");
        }

        @Test
        @DisplayName("rejects targets below the minimum")
        void rejectsSmallTarget() {
            assertThatThrownBy(() -> plain().generate(9, 7))
                    .isInstanceOf(PromoCodeValidationException.class)
                    .hasMessageContaining("Customer target");
        }

        @Test
        @DisplayName("rejects durations outside 1-31")
        void rejectsInvalidDuration() {
            assertThatThrownBy(() -> plain().generate(50, 32))
                    .isInstanceOf(PromoCodeValidationException.class)
                    .hasMessageContaining("Campaign duration");
            assertThatThrownBy(() -> plain().generate(50, 0))
                    .isInstanceOf(PromoCodeValidationException.class)
                    .hasMessageContaining("Campaign duration");
        }

        @Test
        @DisplayName("accepts the boundary values")
        void acceptsBoundaries() {
            assertThat(plain().generate(10, 1)).matches(PromoCodeGenerator.PROMO_CODE_PATTERN);
            assertThat(plain().generate(99, 31)).matches(PromoCodeGenerator.PROMO_CODE_PATTERN);
        }
    }

    @Nested
    @DisplayName("monthly E-J restriction")
    class MonthlyTiers {

        @Test
        @DisplayName("prefers an exclusive tier while one is available")
        void prefersExclusiveTier() {
            // An unused exclusive tier exists, so the code must land on E-J rather than
            // falling back to the reusable A-D pool. Which exclusive tier is chosen is
            // intentionally random, so assert the range, not a specific letter.
            for (int i = 0; i < 100; i++) {
                char tier = plain().generate(50, 7).charAt(6);
                assertThat(tier).isBetween('E', 'J');
            }
        }

        @Test
        @DisplayName("eventually uses every exclusive tier when none are spent")
        void usesTheWholeExclusiveRange() {
            // Guards against a bug that always picked one letter, which would exhaust the
            // available exclusive tiers prematurely.
            Set<Character> seen = new java.util.HashSet<>();
            for (int i = 0; i < 400; i++) {
                seen.add(plain().generate(50, 7).charAt(6));
            }
            assertThat(seen).containsAll(Set.of('E', 'F', 'G', 'H', 'I', 'J'));
        }

        @Test
        @DisplayName("moves off the tiers already spent")
        void advancesThroughExclusiveTiers() {
            // With E and F spent, the chosen tier must be a different, unspent one.
            Set<Character> spent = Set.of('E', 'F');
            for (int i = 0; i < 100; i++) {
                char tier = generator(spent, Set.of()).generate(50, 7).charAt(6);
                assertThat(tier).isBetween('G', 'J');
            }
        }

        @Test
        @DisplayName("falls back to a reusable tier once all six exclusive tiers are spent")
        void fallsBackToReusableTier() {
            Set<Character> spent = Set.of('E', 'F', 'G', 'H', 'I', 'J');
            String code = generator(spent, Set.of()).generate(50, 7);

            assertThat(code.charAt(6)).isIn('A', 'B', 'C', 'D');
        }

        @Test
        @DisplayName("never reuses an exclusive tier already spent this month")
        void neverReusesSpentExclusiveTier() {
            Set<Character> spent = Set.of('E', 'F');
            for (int i = 0; i < 100; i++) {
                char tier = generator(spent, Set.of()).generate(50, 7).charAt(6);
                assertThat(tier).isNotIn('E', 'F');
            }
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

            assertThatThrownBy(() -> allTaken.generate(50, 7))
                    .isInstanceOf(PromoCodeValidationException.class)
                    .hasMessageContaining("unique");
        }

        @Test
        @DisplayName("does not return a code that already exists")
        void skipsTakenCodes() {
            String first = plain().generate(50, 7);
            String second = generator(Set.of(), Set.of(first)).generate(50, 7);

            assertThat(second).isNotEqualTo(first);
        }
    }
}

        @Test
        @DisplayName("classifies A-D as reusable and E-J as exclusive")
        void classifiesTiers() {
            PromoCodeGenerator g = plain();

            assertThat(g.parse("AB1203A").orElseThrow().isExclusiveTier()).isFalse();
            assertThat(g.parse("AB1203D").orElseThrow().isExclusiveTier()).isFalse();
            assertThat(g.parse("AB1203E").orElseThrow().isExclusiveTier()).isTrue();
            assertThat(g.parse("AB1203J").orElseThrow().isExclusiveTier()).isTrue();
        }
    }