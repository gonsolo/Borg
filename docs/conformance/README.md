# Vulkan 1.0 conformance results

`vk1.0-api-direct-sim-*`: `dEQP-VK.api.*` on the direct simulator (tag `conformance/vk1.0-api-direct-sim`).

- Results: one `<status> <case>` line per case, gzipped; totals in `*-summary.txt`.
- CTS: tagged `vulkan-cts-1.4.6.2`; scope: the cases in groups (name depth 4) the 1.0.2.6 mustpass touches.
- Command: `MUSTPASS=1.0.2.6 MUSTPASS_GROUPS=1 JOBS=12 DIRECT=1 scripts/cts_par.sh 'dEQP-VK.api.*'`
- Not a full Vulkan 1.0 submission: Borg RTL only (no CPU, firmware or serial path), api group only.
