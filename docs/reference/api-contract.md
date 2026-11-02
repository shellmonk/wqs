# Projection API contract (as provided, 2026-11-02)

Verbatim from the product owner. Interpretation and resolved ambiguities: spec §7 and §4.1.

```text
GET /web/wallet-data/rest/v1/projection

params:

from -> String (ISO 8601 format date time offset)

to -> String (ISO 8601 format date time offset)

productTypes -> List<String> (array of product types)

transactionTypes -> List<String> (array of transaction types)

excludeCancelledTransactions -> Boolean (default false)

aggregationLevel -> Integer (aggregation level 0 - 2)

pageNumber -> Integer (number of the page)

pageSize -> Integer (page size - it represents a number of top level items)

NOTE: Player ID will be retrieved from access token

 

GET /cc/wallet-data/rest/v1/projection/{playerId}

params:

from -> String (ISO 8601 format date time offset)

to -> String (ISO 8601 format date time offset)

productTypes -> List<String> (array of product types)

transactionTypes -> List<String> (array of transaction types)

excludeCancelledTransactions -> Boolean (default false)

aggregationLevel -> Integer (aggregation level 0 - 2)

pageNumber -> Integer (number of the page)

pageSize -> Integer (page size - it represents a number of top level items)





Aggregation level 0:

flat transactions, nothing is aggregated



{
    "pageNumber": 0,
    "pagesAvailable": 0,
    "pageSize": 0,
    "totalRowCount": 0,
    "data": 
    [
        {
            "txId": "",
            "created": "",
            "productId": "",
            "productType": "",
            "status": "",
            ...
        }, {}, ...
    ]
}
Aggregation level 1:

only sessions are aggregated



{
    "pageNumber": 0,
    "pagesAvailable": 0,
    "pageSize": 0,
    "totalRowCount": 0,
    "data": 
    [
        {
            "productId": "",
            "productType": "",
            "totalPlay": 0.0,
            "totalWin": 0.0,
            "amount": 0.0,
            ...
            "txs": [{
                "txId": "",
                "created": "",
                "productId": "",
                "productType": "",
                "status": "",
                ...
            }, {}, ...]
        },
        {
            "productId": "",
            "productType": "",
            "totalPlay": 0.0,
            "totalWin": 0.0,
            "amount": 0.0,
            ...
            "subitems": 
            {
                "txId": "",
                "created": "",
                "productId": "",
                "productType": "",
                "status": "",
                ...
            }, {}, ...
        }
    ]
}
Aggregation level 2:

both plays and sessions are aggregated



{
    "pageNumber": 0,
    "pagesAvailable": 0,
    "pageSize": 0,
    "totalRowCount": 0,
    "data": 
    [
        {
            "productId": "",
            "productType": "",
            "totalPlay": 0.0,
            "totalWin": 0.0,
            "amount": 0.0,
            ...
            "subitems": [ 
            {
                "playTimestamp": "",
                "playAmount": "",
                "winAmount": "",
                ...
                "txs": [{
                    "txId": "",
                    "created": "",
                    "productId": "",
                    "productType": "",
                    "status": "",
                    ...
                }, {}, ...]
            }, {}, ...]
        },
        {
            "productId": "",
            "productType": "",
            "totalPlay": 0.0,
            "totalWin": 0.0,
            "amount": 0.0,
            ...
            "subitems": [ 
            {
                "playTimestamp": "",
                "playAmount": "",
                "winAmount": "",
                ...
                "txs": [{
                    "txId": "",
                    "created": "",
                    "productId": "",
                    "productType": "",
                    "status": "",
                    ...
                }, {}, ...]
            }, {}, ...]
        },
    ]
}
```
