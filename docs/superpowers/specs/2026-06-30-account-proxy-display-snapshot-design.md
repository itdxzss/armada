# Account Proxy Display Snapshot Design

## Goal

Keep account list country, IP source, and IP address visible after an account releases its current proxy binding.

## Scope

The real proxy allocation state remains in `ip_proxy`: release still clears `bound_account_id` and returns the proxy to IDLE. Account list display falls back to a snapshot stored on `account_state`, so released accounts can still show the proxy details used by the last online attempt.

## Data Model

`account_state` already owns lifecycle/runtime display fields and contains `truth_ip` and `proxy_country`. Add `proxy_source VARCHAR(64) NULL` to store the proxy source snapshot. Reuse existing `proxy_country` and `truth_ip` for the display country and address snapshot.

## Data Flow

When account online allocates a proxy, the account service updates `account_state` with:

- `proxy_country` from the allocated proxy endpoint country
- `truth_ip` from the allocated proxy endpoint host
- `proxy_source` from the allocated `ip_proxy.source`

When state events later release the proxy, only `ip_proxy` binding state changes. The `account_state` snapshot stays intact.

## Account List

The list query reads `country` from `COALESCE(s.proxy_country, p.region)`, `truthIp` from `COALESCE(s.truth_ip, p.host)`, and `ipSource` from `COALESCE(s.proxy_source, p.source)`.

## Testing

Add mapper/service DbTests proving:

- online allocation writes the account proxy snapshot
- account list still returns country/source/IP after the `ip_proxy` binding is released
