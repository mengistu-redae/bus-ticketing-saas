package com.bustix.api.v1;

import com.bustix.cargo.CargoWaybill;
import com.bustix.cargo.CargoWaybillItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A cargo waybill as a partner sees it - the {@code CargoWaybill} entity
 * flattened with its line items. Pricing fields ({@code excessWeightKg}
 * onward) are {@code null} until the shipment is priced at creation. The
 * consignee ID number is included (unlike the public track view) because the
 * partner supplied it and needs it to verify pickup.
 */
public record WaybillV1View(
    UUID id,
    String waybillNumber,
    UUID tripId,
    UUID bookingId,
    UUID operatorId,
    String status,
    String paymentStatus,
    String consignorName,
    String consignorPhone,
    String consignorIdNumber,
    String consigneeName,
    String consigneePhone,
    String consigneeIdNumber,
    String description,
    BigDecimal declaredValue,
    BigDecimal grossWeightKg,
    BigDecimal excessWeightKg,
    BigDecimal baseFreightCharge,
    BigDecimal weightSurcharge,
    BigDecimal handlingServiceFee,
    BigDecimal totalCargoCost,
    Instant createdAt,
    Instant dispatchedAt,
    Instant arrivedAt,
    Instant collectedAt,
    List<ItemView> items
) {

    public record ItemView(
        UUID id,
        String description,
        int quantity,
        BigDecimal declaredValue,
        BigDecimal grossWeightKg
    ) {
    }

    public static WaybillV1View of(CargoWaybill w, List<CargoWaybillItem> items) {
        return new WaybillV1View(
                w.getId(), w.getWaybillNumber(), w.getTripId(), w.getBookingId(), w.getTenantId(),
                w.getStatus(), w.getPaymentStatus(),
                w.getConsignorName(), w.getConsignorPhone(), w.getConsignorIdNumber(),
                w.getConsigneeName(), w.getConsigneePhone(), w.getConsigneeIdNumber(),
                w.getDescription(), w.getDeclaredValue(), w.getGrossWeightKg(), w.getExcessWeightKg(),
                w.getBaseFreightCharge(), w.getWeightSurcharge(), w.getHandlingServiceFee(), w.getTotalCargoCost(),
                w.getCreatedAt(), w.getDispatchedAt(), w.getArrivedAt(), w.getCollectedAt(),
                items.stream()
                        .map(i -> new ItemView(i.getId(), i.getDescription(), i.getQuantity(),
                                i.getDeclaredValue(), i.getGrossWeightKg()))
                        .toList());
    }
}
