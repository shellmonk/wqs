-- Phase 5 ingest rules (spec 4.1, plan D6, D8).

-- D8: a zero-amount item is recorded in the locator ONLY -- so a re-delivery is a duplicate and a
-- cancel referencing it resolves -- and in no item table, rollup, page index or counter.
-- "zeroAmount" tells such a locator-only row apart from a projected item, whose item rows
-- always exist. "cancels" is the target of a locator-only CANCEL: it has no item row to carry
-- it, and the reconciliation oracle must still see that the target is cancelled. Projected rows
-- leave it null; their item rows hold it.
alter table "wqItemLocator"
    add column "zeroAmount" boolean not null default false,
    add column "cancels"    text,
    add constraint "wqItemLocator_cancels_locator_only" check ("cancels" is null or "zeroAmount");

-- D6: a session's (and a play's) product pair is its FIRST item's, first by
-- ("occurredAt", "sourceId", "source"). "startedAt" already is the first item's occurredAt; these
-- hold the rest of that item's identity, the tie-break for items at the same instant, so the
-- label does not depend on arrival order (P-1). Null only on rows written before V11 (none: V10
-- emptied the projection).
alter table "wqSession"
    add column "firstSourceId" text,
    add column "firstSource"   smallint;
alter table "wqPlay"
    add column "firstSourceId" text,
    add column "firstSource"   smallint;
