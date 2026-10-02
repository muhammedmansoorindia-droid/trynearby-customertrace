package com.customertrace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.PromoCode;

/** Promo-code persistence. Uniqueness of {@code code} is enforced by the database. */
public interface PromoCodeRepository extends JpaRepository<PromoCode, Long> {

    Optional<PromoCode> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * The campaign's single promo code.
     *
     * <p>Declared as a {@code List} rather than {@code Optional} on purpose: if a historic row
     * ever held two codes, a single-result lookup would throw an
     * {@code IncorrectResultSizeDataAccessException} and surface as a 500. Returning the list lets
     * callers use the most recent one and fail gracefully instead.
     */
    List<PromoCode> findByCampaignIdOrderByIdDesc(Long campaignId);
}