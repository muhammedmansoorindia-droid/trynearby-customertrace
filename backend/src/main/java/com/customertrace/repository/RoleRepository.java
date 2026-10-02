package com.customertrace.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.Role;

/** Role lookups. Roles are reference data seeded at startup. */
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);
}