package com.bustix.api.v1.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByApiClientIdAndIdempotencyKey(String apiClientId, String idempotencyKey);
}
