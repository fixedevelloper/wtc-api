package com.wtc.reference.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCountryRepository extends JpaRepository<CountryEntity, String> {

    List<CountryEntity> findByEnabledTrueOrderByName();

    Optional<CountryEntity> findByCodeAndEnabledTrue(String code);
}
