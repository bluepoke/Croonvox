# 11. Project license: GPL-3.0

- Status: Accepted (resolves the open point in ADR 1)
- Date: 2026-11-15

## Context
NewPipeExtractor (ADR 1) is GPL-3.0, so any APK that bundles it has to be distributed under GPL-3.0. The repo started as MIT.

## Decision
The whole project is relicensed to **GPL-3.0-or-later** (the form the FSF recommends). `LICENSE` now holds the GPL-3.0 text. Source files carry `SPDX-License-Identifier: GPL-3.0-or-later`.

## Consequences
- All other chosen dependencies are compatible: Signalsmith Stretch (MIT), Demucs (MIT), libFLAC (BSD), LAME (LGPL), Oboe (Apache-2.0), ONNX Runtime (MIT).
- Each Separation Model's weight license still has to be checked individually (ADR 3).
- A future Play-Store flavor stays GPL-3.0 as well. That is legal; it just means its source is published.
