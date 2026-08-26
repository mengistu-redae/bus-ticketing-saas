-- Refines the passenger identity fields added in V3 to match the identity
-- document shape from the Ethiopian bus-ticketing BRD (see
-- my-notes/ethiopian_bus_system_specs.md, section 2.2): a typed document
-- (KEBELE_ID/DIGITAL_ID/PASSPORT/DRIVERS_LICENSE) rather than a bare
-- number with no indication what kind of ID it is. passenger_id_number
-- (added in V3) is unchanged and still holds the document's actual number.
ALTER TABLE booking_seats ADD COLUMN passenger_id_type VARCHAR(20);
