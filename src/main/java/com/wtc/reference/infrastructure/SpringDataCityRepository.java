package com.wtc.reference.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCityRepository extends JpaRepository<CityEntity, Long> {

    List<CityEntity> findByCountryCodeOrderByName(String countryCode);
}
