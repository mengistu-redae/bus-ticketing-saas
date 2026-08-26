package com.bustix.cargo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CargoWaybillItemRepository extends JpaRepository<CargoWaybillItem, UUID> {

    List<CargoWaybillItem> findAllByWaybillId(UUID waybillId);

    /** Used by CargoWaybillService.update's item-replace (delete-all-then-reinsert) semantics. */
    void deleteAllByWaybillId(UUID waybillId);
}
