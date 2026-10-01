CREATE TABLE shows (
    id             UUID PRIMARY KEY,
    name           TEXT        NOT NULL,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT         NOT NULL CHECK (per_user_limit > 0),
    total_seats    INT         NOT NULL CHECK (total_seats > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per physical seat. The row is the unit of mutual exclusion: every state change on a seat
-- happens while holding this row's lock, so there is never a second copy of a seat to sell.
CREATE TABLE seats (
    show_id        UUID        NOT NULL REFERENCES shows (id),
    label          TEXT        NOT NULL,
    position       INT         NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'available'
                               CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id UUID,
    user_id        TEXT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (show_id, label),
    -- A non-available seat always names its owner; an available seat never does.
    CHECK ((status = 'available') = (reservation_id IS NULL)),
    CHECK ((status = 'available') = (user_id IS NULL))
);

CREATE INDEX seats_reservation_idx ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE reservations (
    id              UUID        PRIMARY KEY,
    show_id         UUID        NOT NULL REFERENCES shows (id),
    user_id         TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    seats           TEXT[]      NOT NULL,
    amount_paise    BIGINT      NOT NULL CHECK (amount_paise >= 0),
    status          TEXT        NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at    TIMESTAMPTZ,
    -- Exactly-once: a user's idempotency key can name at most one reservation, ever.
    CONSTRAINT reservations_idem_uq UNIQUE (user_id, idempotency_key)
);

CREATE INDEX reservations_show_idx ON reservations (show_id);

-- Per-user seat counter for a show. Incremented with a conditional upsert so the limit check and the
-- increment are a single atomic statement; parallel requests from one user serialize on this row.
CREATE TABLE user_show_quota (
    show_id    UUID NOT NULL REFERENCES shows (id),
    user_id    TEXT NOT NULL,
    seat_count INT  NOT NULL CHECK (seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);
