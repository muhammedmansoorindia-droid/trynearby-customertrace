package com.customertrace.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.User;

/** User lookups. Queries are parameterised by Spring Data — no string concatenation. */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUserIdIgnoreCase(String userId);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUserIdIgnoreCase(String userId);
}