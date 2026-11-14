# 2. Vocal removal is a core feature

- Status: Accepted (approach still open, see ADR 3)
- Date: 2026-11-14

## Context
Most music videos contain the original lead vocal. Singing over it defeats the purpose of the app.

## Decision
Vocal removal (source separation into an **Instrumental** and a **Vocal Stem**) is in scope for v1 and is a key feature, not an optional extra.

## Consequences
- The Track processing pipeline gets a separation step between extraction and the Session.
- Separation is expensive (ML inference). Its result has to be cached per Track, and the user needs progress feedback.
