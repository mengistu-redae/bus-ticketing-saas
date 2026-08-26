package com.bustix.cargo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CargoWaybillCancellationRepository extends JpaRepository<CargoWaybillCancellation, UUID> {
}
