# AstraSecure — Schema Documentation

Snapshot date: **2026-05-09**
Branch: `master`

This folder documents the complete data model of AstraSecure: what is stored on the device, what is stored on the server, how the two sides map onto each other, and where the current model falls short of what the product actually wants to do.

## Index

| # | File | Audience | Purpose |
|---|------|----------|---------|
| 1 | [01-complete-schema.md](01-complete-schema.md) | Engineers | One‑stop reference of every entity, field, type, and storage location across app + server. |
| 2 | [02-server-schema.md](02-server-schema.md) | Backend / API consumers | Only the data that lives on the relay/sync server (DTOs, endpoints, queues, Signal artefacts). |
| 3 | [03-app-schema.md](03-app-schema.md) | Android engineers | Only the data that lives on the device, plus an explicit **mapping table** showing app ↔ server correspondence. |
| 4 | [04-ideal-schema.md](04-ideal-schema.md) | Product / leads | Non‑technical view of what the schema *should* look like, framed by user‑visible capability: what it would unlock vs. what stays impossible. |

## Reading order

- **New to the project** → 1, then 4.
- **Touching the server** → 2, then mapping section in 3.
- **Touching app state / persistence** → 3.
- **Planning roadmap / scope discussion** → 4.

## Key invariants to know before reading

- Two distinct backends are spoken to from the device:
  - **Signal relay server** — message ciphertext queue + Signal Protocol key directory.
  - **Astra application server** — schema records, missions, channels, clearances (sync via ETag).
- **Plaintext messages never leave the device.** The server only ever sees ciphertext blobs and routing metadata.
- **Messages are not persisted on the device** (ephemeral in‑memory only). Everything else schema‑related is.
- The `createdBy` field exists on Mission and Channel but is **only checked in one place** today (see doc 4 for the consequence).
