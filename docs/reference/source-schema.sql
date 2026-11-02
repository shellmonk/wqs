-- Wallet service source tables (as provided, 2026-11-02). Reference only; not migrated by WQS.

create table public.walaccounttransactions
(
    accounttransactionid       bigint                       not null
        constraint pk_walaccounttransactions
            primary key,
    usertype                   integer                      not null,
    userid                     varchar(64)                  not null,
    accounttype                varchar(32)                  not null,
    accountnumber              varchar(32)                  not null,
    created                    timestamp                    not null,
    currency                   varchar(3),
    amount                     numeric(15, 3)               not null,
    reservedamount             numeric(15, 3)               not null,
    postbalance                numeric(15, 3)               not null,
    transactiontype            varchar(64)                  not null,
    transactionsubtype         varchar(32),
    description                varchar(128),
    fromdescription            varchar(128),
    todescription              varchar(128),
    paymentid                  bigint,
    transactionreference       bigint,
    reservereference           bigint,
    issued                     timestamp                    not null,
    issuertype                 integer,
    issuerid                   varchar(64),
    batchid                    varchar(36),
    subbatchid                 varchar(128),
    channel                    varchar(32),
    subchannel                 varchar(32),
    promotionid                bigint,
    promotionprizeid           bigint,
    sessionminuteid            bigint,
    externalsystemid           varchar(32),
    productid                  varchar(32),
    externaltransactionid      varchar(80),
    uniqueid                   varchar(128),
    uniqueintegrity            bigint,
    approved                   timestamp,
    status                     integer                      not null,
    statusdate                 timestamp                    not null,
    statusdescription          varchar(1024),
    flags                      bigint         default 0     not null,
    xmldata                    varchar(4000),
    reserveendoflife           timestamp,
    retailerid                 varchar(64),
    terminalid                 varchar(64),
    clerkid                    varchar(64),
    createdcontext             text,
    updated                    timestamp                    not null,
    postnonwithdrawablebalance numeric(15, 3)               not null,
    nonwithdrawableamount      numeric(15, 3) default 0     not null,
    wageramount                numeric(15, 3) default 0.0   not null,
    claimid                    bigint,
    prizeserialid              varchar(32),
    aggregationid              varchar(128)                 not null,
    producttype                varchar(32),
    totalpostbalance           numeric(15, 3)               not null,
    monetary                   boolean        default true  not null,
    remoteaddress              varchar(64),
    batchended                 boolean        default false not null,
    postreservedbalance        numeric(12, 3)
);

comment on table public.walaccounttransactions is 'Contains information on balance changes in an account.';

comment on column public.walaccounttransactions.accounttransactionid is 'Primary key of the record. The value is a running number taken from SEQ_walAccountTransactions.';

comment on column public.walaccounttransactions.usertype is 'The type of user this transaction belongs to. See walAccounts.userType.';

comment on column public.walaccounttransactions.userid is 'The id of user this transaction belongs to. See walAccounts.userId.';

comment on column public.walaccounttransactions.accounttype is 'Type of account. For possible values see walAccounts.accountType.';

comment on column public.walaccounttransactions.accountnumber is 'Number of account. See walAccounts.accountNumber.';

comment on column public.walaccounttransactions.created is 'Date and time when account transaction was created.';

comment on column public.walaccounttransactions.currency is 'The currency of the account transaction. See walAccounts.currency';

comment on column public.walaccounttransactions.amount is 'Indicates the change made to the balance of an account (walAccounts.balance).
   A negative amount indicates a reduction in the balance, a positive amount indicates an increase of the balance.';

comment on column public.walaccounttransactions.reservedamount is 'Represents reserved amount from an account, without being withdrawn.';

comment on column public.walaccounttransactions.postbalance is 'The monetary amount of the account after the transaction.';

comment on column public.walaccounttransactions.transactiontype is 'Type of transaction. Possible values are:
   TT_BUY, BUY, BET (for charging for wager purchase - for all of three),
   TT_BUY_UNDO, PLAYER_CANCEL_BUY (for refund due to cancellation of wager purchase),
   TT_BUY_ROLLBACK (for refund due to failure of wager purchase),
   TT_PRIZE_IN, PRIZE (for deposit of winning to account),
   TT_PRIZE_OUT (for withdrawal of winning from account to external payment method),
   TT_PRIZE_OUT_UNDO (for cancellation of wallet withdrawal TT_PRIZE_OUT. This might happen when everything is approved from our side, but a rejection comes from the Bank),
   TT_TRANSFER_IN (for wallet load, accompanied by a payment),
   DEPOSIT (for wallet load, just depositing funds without involving payments),
   TT_TRANSFER_OUT (for wallet withdrawal, accompanied by a payment),
   WITHDRAW (for wallet withdrawal, just withdrawing funds without involving payments),
   TT_TRANSFER_OUT_TAX (for wallet withdrawal tax),
   TT_TRANSFER_OUT_UNDO (for cancellation of wallet withdrawal),
   TT_TRANSFER_IN_UNDO (for cancellation of wallet deposit),
   TT_PERIODIC_CHARGE (for charging for subscription wager),
   TT_REFUND, REFUND (for funds that are refunded),
   TT_REFUND_OUT (for funds that are transferred out of the system either due to deposit limit breaking and customer having been credited or due to customer refund request),
   TT_REFUND_OUT_UNDO (for cancellation/failure of refund transaction TT_REFUND_OUT),
   TT_PRIZE_ROLLBACK, PRIZE_ROLLBACK (for transaction when prize is roll-backed and corrected),
   TT_CLOSING_ACCOUNT (for transactions due to closing account process - funds are transferred to an external payment method),
   TT_CLOSE_ACCOUNT_BENEFICIARY (for transactions due to closing account process when some jurisdictions require a part or all of the funds to be withheld by the government),
   TT_EMPTYING_ACCOUNT (for a transaction where the users account was emptied of funds),
   TT_BLOCKED_ACCOUNT (for a transaction for blocked account -  account is emptied and the funds are transferred to an external payment method),
   TT_BALANCE_BANK (for a balance bank transaction),
   TT_MANUAL_ADJUST (for manual adjustment to wallet accounts, either withdrawal or deposit),
   TT_MANUAL_ADJUST_UNDO (for cancellation of manual adjust payments),
   ADJUST (for wallet adjustment),
   TT_MANUAL_ADJUST_NON_WITHDRAWABLE (for manual adjustment to wallet accounts non-withdrawable balance),
   TT_MANUAL_ADJUST_NON_WITHDRAWABLE_UNDO (for cancellation of manual adjustment to wallet accounts non-withdrawable balance),
   TT_COMMISSION (for deposit to a retailer''s account as commission fee),
   TT_RESERVE (for reserving an amount.  That amount can then be captured, rejected or the transaction be cancelled),
   TT_RESERVE_REJECT (for rejecting an TT_RESERVE amount reservation),
   TT_RESERVE_CANCEL (for cancelling an TT_RESERVE amount reservation),
   RESERVE_FUND_DEPOSIT (for a deposit to a reserve fund),
   RESERVE_FUND_WITHDRAWAL (for a withdrawal from a reserve fund),
   TT_RETAINER_PAYBACK (for paying back retainer amounts),
   TT_WRITE_OFF (for write off a users debt),
   TT_SERVICE_COST (service cost for transferring to or from a bank, or a wagering commission fee),
   TT_TRANSFER_TO_ACCOUNT (for transferring to an account from another account, i.e. in a group system),
   TT_TRANSFER_FROM_ACCOUNT (for transferring from an account to another account, i.e. in a group system),
   TT_EXTERNAL_WALLET_ADJUSTMENT (for updating the balance of a shadow account to match the external wallet it represents),
   TT_TOMBSTONE (for cancellation of a transaction that does not exist in the database),
   TT_CANCEL, CANCEL (for a cancellation of a one phase transaction),
   TT_FRAUD (for a wallet fraud transaction),
   TT_PURGE (for account purge, that is setting the balance of account to zero. Once an account has been purged it cant be used again),
   TT_CHARGEBACK (for chargeback transactions from AT_MAIN account),
   FINISH (for a wallet finish transaction, does not affect the wallet balance),
   SECURE_FUNDING (for a secure funding transaction),
   PROMOTION (for promotion),
   TT_SELF_EXCLUSION (for transactions from AT_MAIN account and if needed from AT_BONUS_PRIZE_CASH_CREDIT account(s) due to permanent self exclusion process)';

comment on column public.walaccounttransactions.transactionsubtype is 'Subtype of transaction. Possible values are:
   TST_LOW_PRIZE (account transaction is linked to a low prize),
   TST_MEDIUM_PRIZE (account transaction is linked to a medium prize),
   TST_HIGH_PRIZE (account transaction is linked to a high prize)
   TST_GRAND_PRIZE (account transaction is linked to a grand prize)
   TST_MANUAL_PRIZE (account transaction is linked to a prize that has to be handled manually)';

comment on column public.walaccounttransactions.description is 'Description of the transaction.';

comment on column public.walaccounttransactions.fromdescription is 'Transferred from';

comment on column public.walaccounttransactions.todescription is 'Transferred to';

comment on column public.walaccounttransactions.paymentid is 'Unique ID for the payment.';

comment on column public.walaccounttransactions.transactionreference is 'Links this account transaction to another transaction if appropriate.';

comment on column public.walaccounttransactions.reservereference is 'When an amount is reserved one reserve account transaction is created and when the amount is captured or released another account transaction is created.
   These two transactions are connected with this reserveReference field.  It gets the value of the first transaction''s accountTransactionId.';

comment on column public.walaccounttransactions.issued is 'Date and time when account transaction was issued.';

comment on column public.walaccounttransactions.issuertype is 'The type of user who made this account transaction.';

comment on column public.walaccounttransactions.issuerid is 'Id of user who made this account transaction.';

comment on column public.walaccounttransactions.batchid is 'Used to batch together multiple transaction that have a logical connection to each other.';

comment on column public.walaccounttransactions.subbatchid is 'Used to provide hierarchical batching possibilities. Many sub-batches can belong to a batch.';

comment on column public.walaccounttransactions.channel is 'Indicates the channel through which the transaction took place.';

comment on column public.walaccounttransactions.subchannel is 'Indicates the subchannel through which the transaction took place.';

comment on column public.walaccounttransactions.promotionid is 'Id of a promotion related to this account transaction, if applicable';

comment on column public.walaccounttransactions.promotionprizeid is 'ID of a promotion prize related to this account transaction if applicable.';

comment on column public.walaccounttransactions.sessionminuteid is 'The session minute ID of the session this transaction was made in.';

comment on column public.walaccounttransactions.externalsystemid is 'The ID of the external system this transaction belongs to.';

comment on column public.walaccounttransactions.productid is 'The ID of product the transaction originated from.';

comment on column public.walaccounttransactions.externaltransactionid is 'The ID of this transaction in an external system.';

comment on column public.walaccounttransactions.uniqueid is 'A unique identifier of this transaction, used to detect retransmissions.';

comment on column public.walaccounttransactions.uniqueintegrity is 'If unique identifier is set, then value for this field is null. If unique identifier is not set, then this is equals to accountTransactionId';

comment on column public.walaccounttransactions.approved is 'Date and time when status was changed from pending.';

comment on column public.walaccounttransactions.status is 'Always 103. This column is scheduled for removal.';

comment on column public.walaccounttransactions.statusdate is 'Date and time when status last changed.';

comment on column public.walaccounttransactions.statusdescription is 'Description of the status, i.e. why does the account transaction have the status in question.';

comment on column public.walaccounttransactions.flags is 'Possible values:
   1 (for special handling of account transactions),
   2 (for account transactions when retransmission is detected),
   4 (if this flag is set, retransmission is allowed for this account transaction),
   8 (for account transactions that should not be grouped to be inserted atomically),
   16 (only allowed for accountType = AT_SUBSYSTEM or AT_PROMOTION.
       If this flag is set on an account transaction the postBalance of this and all future account transactions is recalculated once this transaction has been inserted),
   32 (indicates that the account transaction is using promotional funds),
   64 (when the transaction is allowed to set the account into negative balance)';

comment on column public.walaccounttransactions.xmldata is 'Contains more information about account transaction in xml format, i.e. game, website reference... ';

comment on column public.walaccounttransactions.reserveendoflife is 'When a reserve is made, this time is set to the limit that this transaction must have been settled before';

comment on column public.walaccounttransactions.retailerid is 'The id of the retailer that participated in this transaction';

comment on column public.walaccounttransactions.terminalid is 'The id of the terminal that participated in this transaction';

comment on column public.walaccounttransactions.clerkid is 'The id of the clerk that participated in this transaction';

comment on column public.walaccounttransactions.createdcontext is 'The context in which the request, that is creating this transaction, was created';

comment on column public.walaccounttransactions.updated is 'Date and time when account transaction was updated';

comment on column public.walaccounttransactions.postnonwithdrawablebalance is 'The non-withdrawable monetary balance of the account after the transaction.';

comment on column public.walaccounttransactions.nonwithdrawableamount is 'Indicates the change made to the nonWithdrawableBalance of an account (walAccounts.nonWithdrawableBalance).
   A negative amount indicates a reduction in the balance, a positive amount indicates an increase of the balance.';

comment on column public.walaccounttransactions.wageramount is 'Represents the price of the wager, in case of a prize.';

comment on column public.walaccounttransactions.claimid is 'The Id of a finished claim, in case the account transaction corresponds to a prize that went through
   the claim process. See also walClaimablePrizes.id.';

comment on column public.walaccounttransactions.prizeserialid is 'The serial Id for the prize won if available';

comment on column public.walaccounttransactions.aggregationid is 'Used to aggregate/group related transactions together for transaction history view. For instant games, all transactions in the same batch have the same Id, for other transactions this is unique per transaction.';

comment on column public.walaccounttransactions.producttype is 'The type of product the transaction originated from.';

comment on column public.walaccounttransactions.totalpostbalance is 'The monetary status of all AT_MAIN and AT_BONUS_PRIZE user accounts after the transaction.';

comment on column public.walaccounttransactions.monetary is 'Indicates if transaction is monetary and if it should be taken for totalPostBalance calculation.';

comment on column public.walaccounttransactions.remoteaddress is 'The remote address from where this transaction is initiated.';

comment on column public.walaccounttransactions.batchended is 'Used to signal the last transaction in a batch.';





create table public.walclaimableprizes
(
    id                          bigint                not null
        constraint pk_walclaimableprizes
            primary key,
    playerid                    varchar(64)           not null,
    productid                   varchar(32),
    prizelevel                  varchar(16),
    currency                    varchar(3)            not null,
    prizeamount                 numeric(15, 3)        not null,
    wageramount                 numeric(15, 3)        not null,
    status                      varchar(16)           not null
        constraint walprizelevelclaimstatuses
            check ((status)::text = ANY
                   (ARRAY [('UNCLAIMED'::character varying)::text, ('CLAIMED'::character varying)::text, ('COMPLETED'::character varying)::text, ('REJECTED'::character varying)::text, ('EXPIRED'::character varying)::text])),
    prizeclaimedtimestamp       timestamp,
    claimendedtimestamp         timestamp,
    deductionamounts            jsonb,
    workflowprocessinstanceid   varchar(48),
    created                     timestamp             not null,
    updated                     timestamp             not null,
    accounttype                 varchar(32)           not null,
    accountnumber               varchar(32)           not null,
    description                 varchar(128),
    fromdescription             varchar(128),
    todescription               varchar(128),
    issuertype                  integer,
    issuerid                    varchar(64),
    channel                     varchar(32),
    subchannel                  varchar(32),
    batchid                     varchar(36),
    subbatchid                  varchar(128),
    externaltransactionid       varchar(80),
    externalsystemid            varchar(32),
    xmldata                     varchar(4000),
    uniqueid                    varchar(128)
        unique,
    promotionprizeid            bigint,
    retailerid                  varchar(32),
    terminalid                  varchar(32),
    clerkid                     varchar(32),
    createdcontext              text,
    sessionminuteid             bigint,
    prizeserialid               varchar(32),
    onhold                      boolean default false not null,
    holdreason                  varchar(128),
    claimexpirytimestamp        timestamp,
    aggregationid               varchar(128),
    producttype                 varchar(32),
    workflowstatus              varchar(64),
    workflowstatustimestamp     timestamp,
    holdstatuschangedbyusertype integer,
    holdstatuschangedbyuserid   varchar(64),
    batchended                  boolean default false not null
);

comment on table public.walclaimableprizes is 'Represents prizes that are claimable. After going through a claim process, the prize will be
 paid out to the main wallet, or be dropped if not approved, or expire if not claimed in time.
 A record contains all data necessary to create an account transaction (see walAccountTransactions)
 if the claim is successful.';

comment on column public.walclaimableprizes.id is 'Primary key of the record. The value is a running number taken from SEQ_walClaimablePrizes.';

comment on column public.walclaimableprizes.playerid is 'Id of the player that won the prize (and that has to make a claim to it).';

comment on column public.walclaimableprizes.productid is 'The Id of product (game) the prize originated from.';

comment on column public.walclaimableprizes.prizelevel is 'The prize level for the prize.';

comment on column public.walclaimableprizes.currency is 'The currency of the prize (see walPrizeLevels).';

comment on column public.walclaimableprizes.prizeamount is 'The gross amount of the prize.';

comment on column public.walclaimableprizes.wageramount is 'The amount of the wager for which the prize was won.';

comment on column public.walclaimableprizes.status is 'Status of the claim. Indicates where in the claim lifecycle it is currently.';

comment on column public.walclaimableprizes.prizeclaimedtimestamp is 'Indicates when the prize was claimed.';

comment on column public.walclaimableprizes.claimendedtimestamp is 'Indicates when the claim was moved to final status (COMPLETED or EXPIRED).';

comment on column public.walclaimableprizes.deductionamounts is 'Contains all the deduction amounts applied to the claim.
  JSON object listing all the amounts to be deducted from the prize amount prior to payout.';

comment on column public.walclaimableprizes.workflowprocessinstanceid is 'Id of the process instance for the claim in the Workflow Platform.
  This enables going straight to the Workflow Platform to see how a prize claim is progressing.';

comment on column public.walclaimableprizes.created is 'Indicates whether a message notifying the player that this claim is about to expire has been sent or not';

comment on column public.walclaimableprizes.updated is 'Indicates when the claimable prize was last updated in the database.';

comment on column public.walclaimableprizes.accounttype is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.accountnumber is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.description is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.fromdescription is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.todescription is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.issuertype is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.issuerid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.channel is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.subchannel is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.batchid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.subbatchid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.externaltransactionid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.externalsystemid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.xmldata is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.uniqueid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.promotionprizeid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.retailerid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.terminalid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.clerkid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.createdcontext is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.sessionminuteid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.prizeserialid is 'See corresponding column in walClaimablePrizes.';

comment on column public.walclaimableprizes.onhold is 'Indicates whether claimable prize is put on hold.';

comment on column public.walclaimableprizes.holdreason is 'The reason why is claimable prize on hold.';

comment on column public.walclaimableprizes.claimexpirytimestamp is 'Indicates the timestamp of when the claim is due to expire.';

comment on column public.walclaimableprizes.aggregationid is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.producttype is 'See corresponding column in walAccountTransactions.';

comment on column public.walclaimableprizes.workflowstatus is 'Indicates the sub status of claim within the workflow.';

comment on column public.walclaimableprizes.workflowstatustimestamp is 'Indicates when the workflowStatus was updated.';

comment on column public.walclaimableprizes.holdstatuschangedbyusertype is 'Type of user who changed claim hold status';

comment on column public.walclaimableprizes.holdstatuschangedbyuserid is 'Id of user who changed claim hold status';

comment on column public.walclaimableprizes.batchended is 'See corresponding column in walAccountTransactions.';









create table public.wallimits
(
    id                  bigint      not null
        constraint pk_wallimits
            primary key,
    usertype            integer     not null,
    userid              varchar(64) not null,
    quantitytype        varchar(32) not null,
    amount              numeric(15, 3),
    currency            varchar(3),
    count               integer,
    limittype           varchar(32) not null,
    period              varchar(32) not null,
    gamegroup           varchar(32),
    paymentmethodtype   varchar(32),
    channel             varchar(32),
    effectivefrom       timestamp,
    effectiveuntil      timestamp,
    reason              varchar(128),
    requestedbyusertype integer,
    requestedbyuserid   varchar(64),
    requested           timestamp,
    approvedbyusertype  integer,
    approvedbyuserid    varchar(64),
    approved            timestamp,
    updatedbyusertype   integer     not null,
    updatedbyuserid     varchar(64) not null,
    updated             timestamp   not null,
    createdbyusertype   integer     not null,
    createdbyuserid     varchar(64) not null,
    created             timestamp   not null
);

comment on table public.wallimits is 'Contains information on limits (both global and personal).
   Quantities are either denoted using "amount" + "currency", or "count" (depending on "quantityType").';

comment on column public.wallimits.id is 'Primary key of the record. The value is a running number taken from SEQ_walLimits.';

comment on column public.wallimits.usertype is 'Type of user that the limit is for. In case of a global limit, this value is 0. For personal limits this is 1 (player).';

comment on column public.wallimits.userid is 'Id of user that the limit is for. In case of a global limit, this value is 0. For personal limits this is the player Id.';

comment on column public.wallimits.quantitytype is 'One of the following values:
   MAX_AMOUNT,
   MIN_AMOUNT (global limits only),
   MAX_TX_COUNT,
   BOUNDARY_MAX_AMOUNT (global limits only; indicates that a player can set a personal limit and the maximum amount a user can request),
   BOUNDARY_MAX_TX_COUNT (global limits only; indicates that a player can set a personal limit and the maximum transaction count a user can request),
   SOFT_MAX_AMOUNT (global limits only; indicates the amount above which payments need to be manually handled),
   SOFT_MAX_TX_COUNT (global limits only; indicates the transaction count above which payments need to be manually handled)';

comment on column public.wallimits.amount is 'Monetary amount of the limit request (can only be used if quantityType is xxx_MAX_AMOUNT or xxx_MIN_AMOUNT).';

comment on column public.wallimits.currency is 'Currency of the amount (can only be used if quantityType is xxx_MAX_AMOUNT or xxx_MIN_AMOUNT).';

comment on column public.wallimits.count is 'Transaction count (can only be used if quantityType is xxx_MAX_TX_COUNT).';

comment on column public.wallimits.limittype is 'One of the following values: DEPOSIT, WITHDRAWAL, BUY, LOSS, ACCOUNT_BALANCE, FIRST_DEPOSIT.';

comment on column public.wallimits.period is 'One of the following values: TRANSACTION, SESSION, DAILY, WEEKLY, MONTHLY, YEARLY, ETERNAL.';

comment on column public.wallimits.gamegroup is 'The game group the limit is for. In the absence of a game group value, the value means ALL games.';

comment on column public.wallimits.paymentmethodtype is 'The payment method type the limit is for. In the absence of a payment method value, the value means ALL payment methods.';

comment on column public.wallimits.channel is 'The channel the limit is for. In the absence of a channel value, the value means ALL channels.';

comment on column public.wallimits.effectivefrom is 'Date and time when limit is effective from. If it is not set then it is effective since always.';

comment on column public.wallimits.effectiveuntil is 'Date and time when limit is effective until. If it is not set then it is effective forever.';

comment on column public.wallimits.reason is 'Reason why limit has been changed.';

comment on column public.wallimits.requestedbyusertype is 'The type of user who created limit request.';

comment on column public.wallimits.requestedbyuserid is 'The id of user who created limit request.';

comment on column public.wallimits.requested is 'Date and time when limit was requested.';

comment on column public.wallimits.approvedbyusertype is 'The type of user who approved limit request.';

comment on column public.wallimits.approvedbyuserid is 'The id of user who approved request.';

comment on column public.wallimits.approved is 'Date and time when limit requested was approved.';

comment on column public.wallimits.updatedbyusertype is 'The type of user who updated limit request.';

comment on column public.wallimits.updatedbyuserid is 'The id of user who updated limit request.';

comment on column public.wallimits.updated is 'Date and time when limit requested was updated.';

comment on column public.wallimits.createdbyusertype is 'The type of user who created limit request.';

comment on column public.wallimits.createdbyuserid is 'The id of user who created limit request.';

comment on column public.wallimits.created is 'Date and time when limit requested was created.';







create table public.walmanualadjustmentrequests
(
    id                         bigint         not null
        constraint pk_walmanualadjustmentreq
            primary key,
    usertype                   integer        not null,
    userid                     varchar(64)    not null,
    accounttype                varchar(32)    not null,
    accountnumber              varchar(32)    not null,
    operation                  varchar(10)    not null
        constraint walmanualadjreqcheckoperation
            check ((operation)::text = ANY
                   (ARRAY [('DEPOSIT'::character varying)::text, ('WITHDRAWAL'::character varying)::text])),
    amount                     numeric(15, 3) not null
        constraint walmanualadjreqcheckamount
            check (amount > (0)::numeric),
    currency                   varchar(3)     not null,
    transactiontype            varchar(64)    not null,
    transactionsubtype         varchar(32),
    reason                     varchar(128)   not null,
    issuedbytype               integer        not null,
    issuedbyid                 varchar(64)    not null,
    issued                     timestamp      not null,
    approvedbytype             integer,
    approvedbyid               varchar(64),
    approved                   timestamp,
    rejectedbytype             integer,
    rejectedbyid               varchar(64),
    rejected                   timestamp,
    transactionreference       bigint,
    statusdate                 timestamp      not null,
    status                     varchar(8)     not null
        constraint walmanualadjreqcheckstatus
            check ((status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text])),
    retailerid                 bigint,
    paymentid                  bigint
        constraint fk_walmanualadjustmentrequests_walpayments
            references public.walpayments,
    serviceproviderid          varchar(32),
    bankpaymentid              varchar(64),
    reconciliationmismatchtype varchar(32),
    paymentissueddate          timestamp,
    caseid                     bigint,
    constraint walmanualadjreqapprorrej
        check ((rejected IS NULL) OR (approved IS NULL))
);

comment on table public.walmanualadjustmentrequests is 'Contains requests for manual adjustments. Such requests are then either approved (resulting in the creation of
   a manual adjustment) or rejected.';

comment on column public.walmanualadjustmentrequests.id is 'Primary key of the record. The value is a running number taken from SEQ_walManualAdjustmentReq.';

comment on column public.walmanualadjustmentrequests.usertype is 'Type of user for whom the manual adjustment will be created';

comment on column public.walmanualadjustmentrequests.userid is 'Id of user for whom the manual adjustment will be created';

comment on column public.walmanualadjustmentrequests.accounttype is 'Type of account that the manual adjustment will alter';

comment on column public.walmanualadjustmentrequests.accountnumber is 'Number of account that the manual adjustment will alter';

comment on column public.walmanualadjustmentrequests.operation is 'Identifies whether the manual adjustment is a deposit or withdrawal';

comment on column public.walmanualadjustmentrequests.amount is 'Amount of the manual adjustment';

comment on column public.walmanualadjustmentrequests.currency is 'Currency of the manual adjustment';

comment on column public.walmanualadjustmentrequests.transactiontype is 'Transaction type of the manual adjustment';

comment on column public.walmanualadjustmentrequests.transactionsubtype is 'Transaction subtype of the manual adjustment';

comment on column public.walmanualadjustmentrequests.reason is 'Reason for the manual adjustment';

comment on column public.walmanualadjustmentrequests.issuedbytype is 'Type of user that issued the adjustment request';

comment on column public.walmanualadjustmentrequests.issuedbyid is 'Id of user that issued the adjustment request';

comment on column public.walmanualadjustmentrequests.issued is 'Timestamp indicating when the adjustment request was issued';

comment on column public.walmanualadjustmentrequests.approvedbytype is 'Type of user that approved the adjustment request';

comment on column public.walmanualadjustmentrequests.approvedbyid is 'Id of user that approved the adjustment request';

comment on column public.walmanualadjustmentrequests.approved is 'Timestamp indicating when the adjustment request was approved';

comment on column public.walmanualadjustmentrequests.rejectedbytype is 'Type of user that rejected the adjustment request';

comment on column public.walmanualadjustmentrequests.rejectedbyid is 'Id of user that rejected the adjustment request';

comment on column public.walmanualadjustmentrequests.rejected is 'Timestamp indicating when the adjustment request was rejected';

comment on column public.walmanualadjustmentrequests.transactionreference is 'In case of an approved request, this is the Id of the account transaction that was generated as a result.';

comment on column public.walmanualadjustmentrequests.statusdate is 'Timestamp indicating when the status was last changed. This can be used to detect when a record was last changed.';

comment on column public.walmanualadjustmentrequests.status is 'Status of the adjustment request';

comment on column public.walmanualadjustmentrequests.retailerid is 'RetailerId (location) associated with the manual adjustment';

comment on column public.walmanualadjustmentrequests.paymentid is 'ID of payment for which this manual adjustment is requested';

comment on column public.walmanualadjustmentrequests.serviceproviderid is 'ID of service provider of payment for which this manual adjustment is requested';

comment on column public.walmanualadjustmentrequests.bankpaymentid is 'External ID (PSP payment ID) of payment for which this manual adjustment is requested';

comment on column public.walmanualadjustmentrequests.reconciliationmismatchtype is 'Mismatch type when manual adjustment is requested for fixing reconciliation mismatch';

comment on column public.walmanualadjustmentrequests.paymentissueddate is 'Date when adjustment payment was issued';

comment on column public.walmanualadjustmentrequests.caseid is 'ID of the case manual adjustment request belongs to';




create table public.walpayments
(
    paymentid                  bigint                                           not null
        constraint pk_walpayments
            primary key,
    paymentinout               integer                                          not null,
    paymenttype                varchar(32),
    ownertype                  integer                                          not null,
    ownerid                    varchar(64)                                      not null,
    customerid                 varchar(64),
    retailerid                 bigint,
    clerkid                    bigint,
    terminal                   varchar(32),
    channel                    varchar(32),
    subchannel                 varchar(32),
    created                    timestamp                                        not null,
    approved                   timestamp,
    currency                   varchar(3),
    amount                     numeric(15, 3)                                   not null,
    description                varchar(256),
    fromdescription            varchar(128),
    todescription              varchar(128),
    bankpaymentid              varchar(64),
    destinationaccounttype     varchar(32),
    destinationaccountnumber   varchar(32),
    servicecost                numeric(15, 3) default 0.0                       not null,
    bankbatchnumber            varchar(32),
    payserviceproviderid       varchar(32),
    payserviceprovidertargetid varchar(32),
    promotioncode              varchar(64),
    promotionprizeid           bigint,
    status                     integer                                          not null,
    statusdate                 timestamp                                        not null,
    statusdescription          varchar(1024),
    flags                      bigint         default 0                         not null,
    xmldata                    varchar(10000),
    paypaymethodid             varchar(64),
    paypaytype                 integer,
    paycardname                varchar(32),
    paycardtype                varchar(32),
    paycardnumber              varchar(72),
    paycardexpdateday          varchar(48),
    paycardexpdatemonth        varchar(48),
    paycardexpdateyear         varchar(48),
    paybankid                  varchar(32),
    paybanktype                varchar(32),
    paybankaccount             varchar(72),
    paybankpersonalid          varchar(128),
    payexternalreferenceid     varchar(36),
    chunkid                    bigint
        constraint fk_walpaymentchunkid
            references public.walpaymentchunks,
    updated                    timestamp                                        not null,
    payrealownername           varchar(92),
    payfingerprint             varchar(64),
    payemail                   varchar(220),
    exportdate                 timestamp,
    confirmedbypsp             timestamp,
    grossamount                numeric(15, 3)                                   not null,
    jsondata                   varchar(4000),
    payswiftcode               varchar(48),
    taxreference               bigint,
    payserial                  varchar(72),
    paybankname                varchar(64),
    paystatus                  varchar(32),
    payverificationstatus      varchar(32),
    payjsondata                text,
    errorcategory              varchar(64),
    payroutingnumber           varchar(48),
    payaccounttype             varchar(8),
    reviewhandling             varchar(10)    default 'NONE'::character varying not null,
    previouslyinbatch          boolean        default false                     not null,
    createdbyusertype          integer,
    createdbyuserid            varchar(64),
    paycardbin                 varchar(8),
    exterrorcode               varchar(64),
    paymentreference           bigint
        constraint fk_walpaymentreference
            references public.walpayments,
    updatedbyusertype          integer,
    updatedbyuserid            varchar(64),
    remoteaddress              varchar(64),
    workflowstatus             varchar(32),
    issued                     timestamp                                        not null,
    verified                   timestamp,
    reasoncode                 varchar(64),
    reasondescription          varchar(256),
    inreconciliationfile       boolean        default false                     not null,
    externalreferenceid        varchar(32),
    payexternaluserid          varchar(32),
    constraint walpaymentsgrossamount
        check (abs(grossamount) >= abs(amount))
);

comment on table public.walpayments is 'Contains information on funds going in or out of the system.';

comment on column public.walpayments.paymentid is 'Primary key of the record. The value is a running number taken from SEQ_walPayments';

comment on column public.walpayments.paymentinout is 'The direction of the payment, IN or OUT of the system. Possible values: IN = 1, OUT = 2';

comment on column public.walpayments.paymenttype is 'Type of the payment. Possible values are:
   PT_BUY (indicates that the payment was made for wager purchase),
   PT_BUY_UNDO (indicates that the payment was made to refund a cancelled wager),
   PT_RETAILER_BUY (indicates that the payment was made for buying wager through a retailer),
   PT_RETAILER_BUY_UNDO (indicates that the payment was made for refunding a canceled wager through a retailer),
   PT_COMMISSION (indicates that the payment was made for paying commission fee to a retailer),
   PT_PRIZE (indicates that the type of payment is prize payout),
   PT_MEDIUM_PRIZE (indicates that the payment was made for paying our medium prize),
   PT_HIGH_PRIZE (indicates that the payment was made for paying out high prize),
   PT_GRAND_PRIZE (indicates that the payment was made for paying out grand prize),
   PT_MANUAL_PRIZE (indicates that the payment was made for paying out to default out, but the player does not have a default out so this is marked as a manual payment),
   PT_PERIODIC_PRIZE (indicates that the payment was made for for periodic prize),
   PT_TRANSFER_IN (indicates a wallet load payment),
   PT_TRANSFER_OUT (indicates a wallet withdrawal payment),
   PT_PERIODIC_CHARGE (indicates that the type of payment is a subscription charge),
   PT_CLOSING_ACCOUNT (indicates that the payment was made for transferring funds from wallet due to closing account),
   PT_BLOCKED_ACCOUNT (indicates that the payment was made for transferring funds from wallet due to blocking the account),
   PT_BALANCE_BANK (indicates that the payment was made for balance bank),
   PT_MANUAL_ADJUST (indicates that payment was made manually by a backoffice user. Payments of this type are going through PSP processing),
   PT_MANUAL_ADJUST_RECONCILIATION (indicates that payment was made for adjusting payments when there are mismatches between Wallet and PSP that can be identified manually or through the reconciliation process. Payments of this type dont go through PSP processing and are used only as correction items),
   PT_REFUND (indicates payment that is made to refund player when he has credited the PSP but the deposit broke a limit),
   PT_FRAUD (indicates a wallet fraud payment),
   PT_EMPTYING_INACTIVE_ACCOUNT (indicates that the payment was made for transferring funds from wallet due to account inactivity),
   PT_SELF_EXCLUSION (indicates the withdrawal payment that is made when player is permanently self excluded)';

comment on column public.walpayments.ownertype is 'User type of the owner of this payment';

comment on column public.walpayments.ownerid is 'User id of the owner of this payment';

comment on column public.walpayments.customerid is 'The unique reference of the customer';

comment on column public.walpayments.retailerid is 'The unique reference of the retailer';

comment on column public.walpayments.clerkid is 'The unique reference of the clerk';

comment on column public.walpayments.terminal is 'Unique id of the terminal';

comment on column public.walpayments.channel is 'Through which channel the payment took place. CHANNEL_ALL is not allowed here';

comment on column public.walpayments.subchannel is 'Indicates the subchannel through which the payment took place.
   If the channel were CHANNEL_MOBILE for example, this field could indicate whether the mobile client was an iPhone client, an Android client or any other.';

comment on column public.walpayments.created is 'Date and time when the payment is created';

comment on column public.walpayments.approved is 'Date and time when the payment is approved';

comment on column public.walpayments.currency is 'Currency of the payment amount';

comment on column public.walpayments.amount is 'Monetary amount of the payment. This is the actual amount being transferred in or out of the system after possible taxes have been subtracted.';

comment on column public.walpayments.description is 'Payment description';

comment on column public.walpayments.fromdescription is 'Transferred from';

comment on column public.walpayments.todescription is 'Transferred to';

comment on column public.walpayments.bankpaymentid is 'Bank ID of the payment';

comment on column public.walpayments.destinationaccounttype is 'Type of account that the payment is being transferred to. Possible values are: walAccounts.accountType';

comment on column public.walpayments.destinationaccountnumber is 'Account number that the payment is being transferred to';

comment on column public.walpayments.servicecost is 'The service cost (charged by payment provider/gateway) for performing the payment';

comment on column public.walpayments.bankbatchnumber is 'Bank batch number.  Received from either a bank or a credit card company';

comment on column public.walpayments.payserviceproviderid is 'Payment provider Id. See walServiceProviders.serviceProviderId.';

comment on column public.walpayments.payserviceprovidertargetid is 'Payment provider target Id. See walServiceProviderTargets.serviceProviderTargetId.';

comment on column public.walpayments.promotioncode is 'Promotion code of a promotion that is being activated by this payment';

comment on column public.walpayments.promotionprizeid is 'ID of a promotion prize that is being activated by this payment';

comment on column public.walpayments.status is 'Status of the payment. Possible values are:
   PENDING = 1 (indicates that the payment is pending),
   APPROVED = 2 (indicates that the payment has been approved),
   VERIFIED = 3 (indicates that the payment is verified),
   FIXED = 4 (indicates that the payment has been manually fixed and needs to be put back in a different status for further processing),
   CANCELLED = 5 (indicates that the payment has been cancelled),
   REJECTED = 6 (indicates that the payment has been rejected),
   REVIEW = 7 (indicates that the payment needs to be reviewed before being processed further),
   EXPIRED = 8 (indicates that the payment has expired)';

comment on column public.walpayments.statusdate is 'Date and time when the status was last changed';

comment on column public.walpayments.statusdescription is 'Description of the status, i.e. why does the payment have the status in question';

comment on column public.walpayments.flags is 'Possible values are:
   MANUALLY_HANDLED = 1 (indicates that the payment was handled manually, as opposed to automatically),
   SERVICE_COST_CHARGED_INTERNALLY = 2 (indicates that service cost is charged internally),
   CAPTURED = 4 (indicates that the payment has been captured by an external Payment Service Provider),
   EXPORTED = 8 (indicates that the payment has been exported and verified against an external Payment Service Provider),
   EXPORT_INVALID = 16 (indicates that the payment has been exported and verified against an external Payment Service Provider),
   FORCE_STATUS_APPROVED_ON_MANUALLY_HANDLED = 32 (indicates that the payment status of a manually handled payment should be APPROVED),
   FLAG_HIDE_FROM_APPROVAL_LIST = 64 (indicates that the payment should be hidden from backoffice approval list),
   REFUNDED = 128 (indicates that the payment has been refunded)';

comment on column public.walpayments.xmldata is 'Contains more information about payment in xml format, i.e. id of external transaction. Encrypted.';

comment on column public.walpayments.paypaymethodid is 'Id of payment method assigned to the payment. See walPaymentMethods.paymentMethodId';

comment on column public.walpayments.paypaytype is 'Payment method type. See walPaymentMethods.paymentMethodType';

comment on column public.walpayments.paycardname is 'Name of the card used. See walPaymentMethods.cardName';

comment on column public.walpayments.paycardtype is 'Describes whether the card is a debit or credit card. See walPaymentMethods.cardType';

comment on column public.walpayments.paycardnumber is 'Number of a payment card, masked, encrypted. See walPaymentMethods.cardNumber';

comment on column public.walpayments.paycardexpdateday is 'Expiry day of payment card, encrypted. See walPaymentMethods.cardExpDateDay';

comment on column public.walpayments.paycardexpdatemonth is 'Expiry month of payment card, encrypted. See walPaymentMethods.cardExpDateMonth';

comment on column public.walpayments.paycardexpdateyear is 'Expiry year of payment card, encrypted. See walPaymentMethods.cardExpDateYear';

comment on column public.walpayments.paybankid is 'ID of bank used for payment. See walPaymentMethods.bankId';

comment on column public.walpayments.paybanktype is 'The type of the bank account details. See walPaymentMethods.bankType';

comment on column public.walpayments.paybankaccount is 'Bank account number, encrypted. See walPaymentMethods.bankAccount';

comment on column public.walpayments.paybankpersonalid is 'Personal ID number for bank payments, encrypted. See walPaymentMethods.bankPersonalId';

comment on column public.walpayments.payexternalreferenceid is 'Reference id for external payments. See walPaymentMethods.externalReferenceId';

comment on column public.walpayments.chunkid is 'Payment chunk Id. See walPaymentChunks.chunkId.';

comment on column public.walpayments.updated is 'Date and time when the payment is updated';

comment on column public.walpayments.payrealownername is 'Real owner name of bank payment method, encrypted. See walPaymentMethods.realOwnerName';

comment on column public.walpayments.payfingerprint is 'A unique identifier for card payment. See walPaymentMethods.fingerprint';

comment on column public.walpayments.payemail is 'Email used for external payments, encrypted. See walPaymentMethods.email';

comment on column public.walpayments.exportdate is 'Date when payment should be exported';

comment on column public.walpayments.confirmedbypsp is 'Timestamp indicating when the payment was confirmed by a Payment Service Provider (PSP)';

comment on column public.walpayments.grossamount is 'The amount of the payment before (potential) taxes have been subtracted';

comment on column public.walpayments.jsondata is 'Arbitrary client specific data. One example of data that might be stored here is a client specific receipt for withdrawal/deposit.';

comment on column public.walpayments.payswiftcode is 'SWIFT code (see ISO 9362). Encrypted.';

comment on column public.walpayments.taxreference is 'Used to link payments that are taxed together. Values are generated by using SEQ_walTaxReferences.';

comment on column public.walpayments.payserial is 'Identification of prepaid payment method. Encrypted.';

comment on column public.walpayments.paybankname is 'ID of bank used for payment. See walPaymentMethods.bankName';

comment on column public.walpayments.paystatus is 'See corresponding column in walPaymentMethods.status';

comment on column public.walpayments.payverificationstatus is 'See corresponding column in walPaymentMethods.verificationStatus';

comment on column public.walpayments.payjsondata is 'See corresponding column in walPaymentMethods.jsonData. Encrypted.';

comment on column public.walpayments.errorcategory is 'Stores Platform mapped error category names from payment processors.
  see example of error categories here https://wiki.nextgl.com/display/PHOENIX/Error+Mapping+to+Categories';

comment on column public.walpayments.payroutingnumber is 'The routing number for ACH bank accounts, encrypted.';

comment on column public.walpayments.payaccounttype is 'The account type for ACH bank accounts.';

comment on column public.walpayments.reviewhandling is 'Indicates how review should be performed for the payment.
   Possible values are:
   NONE (Not configured to be use),
   RISK (Payment is handled by risk workflow as risk service marked it as a risky),
   MANUAL (Requires manual approval by a Backoffice admin),
   AUTOMATIC (Payment is handled by regular withdrawal review workflow as risk service marked it as not risky),
   REGULAR (Payment is handled by regular withdrawal review workflow)';

comment on column public.walpayments.previouslyinbatch is 'Indicates whether this payment was previously in a batch.
   This is set to true when payment is removed from batch and should not be set to false after that.';

comment on column public.walpayments.createdbyusertype is 'User type for the user that created manual withdrawal payment.';

comment on column public.walpayments.createdbyuserid is 'User id for the user that created manual withdrawal payment.';

comment on column public.walpayments.paycardbin is 'Number of a payment card bin. See walPaymentMethods.cardBin';

comment on column public.walpayments.exterrorcode is 'Stores Payment Processor original error code names from payment processors.
  see example of error categories here https://wiki.nextgl.com/display/PHOENIX/Error+Mapping+to+Categories';

comment on column public.walpayments.paymentreference is 'Link payment to the original payment e.g. PT_REFUND payment to refunded payment.';

comment on column public.walpayments.updatedbyusertype is 'See corresponding column in walPayments';

comment on column public.walpayments.updatedbyuserid is 'See corresponding column in walPayments';

comment on column public.walpayments.remoteaddress is 'The remote address from where this payment is initiated.';

comment on column public.walpayments.workflowstatus is 'Used as an additional status for payments that go through workflows which do not affect walPayments.status.';

comment on column public.walpayments.issued is 'Date when payment is issued, in the case of PT_MANUAL_ADJUSTMENT_RECONCILIATION it can represent the time in the past when payment is created in PSP.';

comment on column public.walpayments.verified is 'Timestamp indicating when the payment was verified';

comment on column public.walpayments.reasoncode is 'Stores chargeback attribute from file - CBK_REASON_CDE';

comment on column public.walpayments.reasondescription is 'Stores chargeback attribute from file - CBK_REASON_DESCRIPTION';

comment on column public.walpayments.inreconciliationfile is 'Indicates whether a payment has been added to a reconciliation file.';

comment on column public.walpayments.externalreferenceid is 'An external reference to the payment';

comment on column public.walpayments.payexternaluserid is 'Reference id for external payments method. See walPaymentMethods.payExternalUserId';
