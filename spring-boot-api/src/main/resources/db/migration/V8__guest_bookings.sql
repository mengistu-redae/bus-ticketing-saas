-- Guest (no-account) bookings: bookings.customer_user_id and channel already
-- allowed NULL/any-string respectively since V1 (no NOT NULL on the former,
-- no CHECK on the latter), so a channel = 'guest' booking with
-- customer_user_id NULL needs no change to either column. The one thing a
-- guest booking needs that nothing else stores is a durable contact phone -
-- without it, a guest who navigates away (or comes back later) has no way to
-- look their own booking back up via GET /api/bookings/guest/track/{ref}.
-- Nullable: only ever set for channel = 'guest' bookings; NULL for
-- self_service/counter, same as agent_user_id is NULL outside channel =
-- counter.
ALTER TABLE bookings
    ADD COLUMN guest_contact_phone VARCHAR(20);
