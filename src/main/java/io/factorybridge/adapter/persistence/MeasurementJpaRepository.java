package io.factorybridge.adapter.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MeasurementJpaRepository extends JpaRepository<MeasurementEntity, UUID> {
    Optional<MeasurementEntity> findBySourceAndSourceRecordId(String source, String sourceRecordId);
}
