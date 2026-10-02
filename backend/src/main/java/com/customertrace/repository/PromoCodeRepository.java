package com.customertrace.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.PromoCode;

/** Promo-code persistence. Uniqueness of {@code code} is enforced by the database. */
public interface PromoCodeRepository extends JpaRepository<PromoCode, Long> {

    Optional<PromoCode> findByCode(String code);

    boolean existsByCode(String code);
}