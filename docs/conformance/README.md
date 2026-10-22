# Vulkan 1.0 conformance results

Status, per-group numbers and limits: [STATUS.md](STATUS.md).

`vk1.0-api-direct-sim-*`: `dEQP-VK.api.*` on the direct simulator (tag `conformance/vk1.0-api-direct-sim`).

- Results: one `<status> <case>` line per case, gzipped; totals in `*-summary.txt`.
- CTS: tagged `vulkan-cts-1.4.6.2`; scope: the cases in groups (name depth 4) the 1.0.2.6 mustpass touches.
- Command: `MUSTPASS=1.0.2.6 MUSTPASS_GROUPS=1 JOBS=12 DIRECT=1 scripts/cts_par.sh 'dEQP-VK.api.*'`
- Not a full Vulkan 1.0 submission: Borg RTL only (no CPU, firmware or serial path), api group only.

`vk1.0-pipeline-state-direct-sim-results.txt.gz`: `pipeline.monolithic` blend, depth, stencil and multisample
subgroups in one run (2026-10-22), same line format.

`vk-default-fraction0of16-direct-sim-results.txt.gz`: fraction 0 of 16 of the 1.4.6.2 mustpass `vk-default.txt`
(2026-10-22), `FRACTION=0,16 JOBS=16 DIRECT=1 scripts/cts_par.sh fraction0of16`. Results only, not the qpa a
submission needs.
