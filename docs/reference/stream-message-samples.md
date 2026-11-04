# RabbitMQ stream message samples (as provided, 2026-11-02)

Captured walAccountTransactions events. Golden fixtures for the stream decoder (spec §4.1).

```text
┌── Message #415  (offset: 414) ────────────────────────────────────────
│  Message ID    : 1294
│  aggregateType : walAccounts
│  aggregateId   : AT_MAIN-0
│  eventType     : TT_TRANSFER_OUT
│  partitionKey  : 1717
│  created       : 2026-10-29 11:22:04.456 UTC
│  Payload:
│    {
│        "id": "5962",
│        "type": "TT_TRANSFER_OUT",
│        "amount": {
│            "amount": -12,
│            "currency": "EUR",
│            "reservedAmount": 0,
│            "nonWithdrawableAmount": 0
│        },
│        "account": "AT_MAIN-0",
│        "subtype": "",
│        "monetary": true,
│        "productId": "",
│        "references": {
│            "claimId": "",
│            "extTrxId": "",
│            "paymentId": "6429",
│            "extSystemId": "",
│            "transactionReference": "5962"
│        },
│        "description": "",
│        "userContext": {
│            "ownerId": "1-1717",
│            "issuerId": "1-1717"
│        },
│        "batchContext": {
│            "batchId": "",
│            "batchEnded": false,
│            "subBatchId": ""
│        },
│        "toDescription": "WITHDRAWAL*5208",
│        "accountContext": {
│            "accountType": "AT_MAIN",
│            "accountNumber": "0"
│        },
│        "createdContext": {
│            "issued": "2026-10-29T11:22:04.465648Z",
│            "channel": "WEB",
│            "created": "2026-10-29T11:22:04.465648Z"
│        },
│        "fromDescription": "AT_MAIN",
│        "totalPostBalance": 178
│    }
└──────────────────────────────────────────────────────────────────────





┌── Message #412  (offset: 411) ────────────────────────────────────────
│  Message ID    : 1291
│  aggregateType : walAccounts
│  aggregateId   : AT_MAIN-0
│  eventType     : PRIZE
│  partitionKey  : 1945
│  created       : 2026-10-26 11:36:23.048 UTC
│  Payload:
│    {
│        "id": "5922",
│        "type": "PRIZE",
│        "amount": {
│            "amount": 0,
│            "currency": "EUR",
│            "reservedAmount": 0,
│            "nonWithdrawableAmount": 0
│        },
│        "account": "AT_MAIN-0",
│        "subtype": "",
│        "monetary": true,
│        "productId": "TIKIKALACABANA",
│        "references": {
│            "claimId": "",
│            "extTrxId": "PS-62pflumf4bcz7jdtfc3wl5yqfm-1-prize",
│            "paymentId": "",
│            "extSystemId": "PDS",
│            "transactionReference": "5922"
│        },
│        "description": "Payout Play Step",
│        "productType": "instants",
│        "userContext": {
│            "ownerId": "1-1945",
│            "issuerId": "1-1945"
│        },
│        "batchContext": {
│            "batchId": "GS-ur5z5sgfr5de5flhjd6ycryf6u",
│            "batchEnded": true,
│            "subBatchId": "PS-62pflumf4bcz7jdtfc3wl5yqfm"
│        },
│        "toDescription": "PS-62pflumf4bcz7jdtfc3wl5yqfm",
│        "accountContext": {
│            "accountType": "AT_MAIN",
│            "accountNumber": "0"
│        },
│        "createdContext": {
│            "issued": "2026-10-26T11:36:17.357Z",
│            "channel": "WEB",
│            "created": "2026-10-26T11:36:23.049062Z"
│        },
│        "fromDescription": "AT_MAIN",
│        "totalPostBalance": 1156
│    }
└──────────────────────────────────────────────────────────────────────
```
