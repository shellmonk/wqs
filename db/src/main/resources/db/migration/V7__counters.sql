-- Tier 1: whole months. Periods are UTC (spec 7.1). Rows are sparse -- they exist only for
-- periods where the user had activity.
--
-- Counters are mutable in place: a cancel arriving today can decrement a counter for a
-- transaction from eight months ago, which rules out append-only storage (spec 5.7).
create table "wqCountMonth" (
    "userId"            bigint   not null,
    "viewKind"          smallint not null,  -- 1 = WEB, 2 = CC
    "pageUnit"          smallint not null,  -- 0 = ITEM (L0), 1 = HIGH_LEVEL (L1/L2)
    "productId"         int      not null,  -- 0 = ALL-PRODUCTS sentinel
    "period"            date     not null,  -- first day of the month, UTC
    "cntAll"            bigint   not null default 0,
    "cntExclCancelled"  bigint   not null default 0,
    check ("cntAll" >= 0 and "cntExclCancelled" >= 0 and "cntExclCancelled" <= "cntAll"),
    primary key ("userId", "viewKind", "pageUnit", "productId", "period")
);

-- Tier 2: whole days, for the partial months at each end of a requested range.
create table "wqCountDay" (
    "userId"            bigint   not null,
    "viewKind"          smallint not null,
    "pageUnit"          smallint not null,
    "productId"         int      not null,
    "period"            date     not null,
    "cntAll"            bigint   not null default 0,
    "cntExclCancelled"  bigint   not null default 0,
    check ("cntAll" >= 0 and "cntExclCancelled" >= 0 and "cntExclCancelled" <= "cntAll"),
    primary key ("userId", "viewKind", "pageUnit", "productId", "period")
) partition by range ("period");
