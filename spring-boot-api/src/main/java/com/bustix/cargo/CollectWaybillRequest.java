package com.bustix.cargo;

import jakarta.validation.constraints.NotBlank;

/** The ID document number presented at pickup, checked against consigneeIdNumber on file - see CargoWaybillService.collect. */
public record CollectWaybillRequest(
    @NotBlank String presentedIdNumber
) {
}
