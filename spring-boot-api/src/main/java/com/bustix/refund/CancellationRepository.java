package com.bustix.refund;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CancellationRepository extends JpaRepository<Cancellation, UUID> {
}
