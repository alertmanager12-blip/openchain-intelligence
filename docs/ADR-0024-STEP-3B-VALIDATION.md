# ADR-0024 step 3b: memory validation

Status: **final for this run — verdict: improvement, to be confirmed** (2026-11-11).
Branch `wip/adr-0024-step-3a` (step 3a `7b667ca`, step 3b `299db4f`), not pushed. The image measured is `299db4f`;
the later commits of the branch (`1115044` detector fixes, `53566bd` reorganisations) were **not** deployed and did not
affect this run.
Every value below comes from the collected data; none is extrapolated. Raw data: `mem-3b.tsv` (session scratchpad,
not committed).

## 1. Executive summary

- Step 3b removed what made step 3a fail: **0 full GC** in 2 h 42 (269 in 3 h with step 3a alone), no restart, no
  out-of-memory; the profile cap holds exactly (≈ 300,000 at every trim).
- **Memory is not bounded yet.** After the caps started binding, live data after GC kept rising by **≈ 67 MB/h**
  (21:22 → 23:04). Two populations explain it, both found in the data:
  1. **signals of wallets that still have a profile** are never removed: 174,795 → 269,676 (≈ 56,000/h) for a cap of
     150,000 (known defect, P0 item 3);
  2. **flows of exempt addresses** (loaded histories, never trimmed nor counted by the cap) grow by ≈ 18,000/h above the
     cap (lower bound; analysed in `docs/EXEMPT_ADDRESSES_ANALYSIS.md`).
- The run measured an Ethereum **catch-up** (about 4,000 blocks behind throughout), not the steady state, and it was
  **cut by the machine going to sleep after 23:04** (33 valid readings, 20:22 → 23:04). The heap histogram was not
  taken within the run; one taken after wake-up (3.4) shows that strings, mostly the per-profile de-duplication keys,
  are about half of the live data.
- **Verdict: improvement, to be confirmed** (section 9). Not declared successful on the absence of full GC alone.

## 2. Measurement set-up

| Item | Value |
|---|---|
| Image | `openchain-intelligence/app:0.1.0-SNAPSHOT` = previous image with the jar of commit `299db4f` swapped in (built locally) |
| Container | 4 GB limit, JVM `-XX:MaxRAMPercentage=75` (heap max 3 GB; old generation max 3,221,225,472 bytes), `-XX:+PrintClassHistogram` |
| Networks | Ethereum mainnet and TRON, real providers |
| Windows | defaults: flows 24 h, signals 48 h, profiles idle 7 days (compose override; the operator's `.env` with 2 h was not changed) |
| Caps | 400,000 flows, 300,000 profiles, 150,000 signals; trim every 10 minutes |
| Journal (step 2) | off |
| Docker VM | 8 GB (7,935 MB visible); other stacks stopped; only ChainSentinel's app, PostgreSQL and frontend |
| Sampling | every 5 minutes: container memory, old generation used, live data after GC, full GC count, working-set sizes after the last trim, events indexed |
| Exempt addresses | watchlist: 0; address histories: **65** (corrected 2026-11-11; the first version said 10, from a truncated query; detail in `docs/EXEMPT_ADDRESSES_ANALYSIS.md`) |

**Load**: Ethereum about 4,400 blocks behind at 21:20 and about 4,000 at 22:30 (catch-up never finished); events
indexed ≈ 662,000/h between 21:22 and 23:04, against ≈ 200,000 flows/h at the steady state measured on 2026-11-09.

**Interruption**: the machine (on battery) went to sleep after 23:04; the sampler, the Docker VM and the app were
suspended together (no restart, no OOM: `restarts=0 oom=false`). A reading at 08:24 after wake-up is excluded (9-hour
gap, app suspended). The class histogram, planned at the end of the sampler, was not produced within the run.

## 3. Metrics

### 3.1 Reference: step 3a alone (2026-11-10 10:57-13:50, all windows at 2 h, same container)

Old generation 2.92 GB of 3 GB and rising; 269 full GCs (445.7 s of cumulated pauses); 961,581 flows, 301,021 signals,
650,496 profiles, all rising; no restart in about 3 hours.

### 3.2 Step 3b, full series (valid readings)

| Time | Container | Old gen (MB) | Live after GC (MB) | Full GC | Flows | Signals | Profiles | Trim (ms) | Events indexed |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 20:22 | 1.061 GiB | 116 | 93 | 0 | – | – | – | – | 13,713 |
| 20:32 | 2.018 GiB | 925 | 925 | 0 | 256,723 | 49,253 | 222,940 | 650 | 241,438 |
| 20:42 | 2.654 GiB | 1,299 | 1,293 | 0 | 394,332 | 85,641 | 300,039 | 817 | 372,648 |
| 20:52 | 3.125 GiB | 1,461 | 1,457 | 0 | 434,710 | 110,557 | 299,792 | 1,174 | 498,542 |
| 21:02 | 3.526 GiB | 1,659 | 1,659 | 0 | 437,035 | 135,271 | 299,781 | 1,455 | 610,803 |
| 21:22 | 3.532 GiB | 1,786 | 1,786 | 0 | 442,661 | 174,795 | 299,132 | 1,034 | 805,773 |
| 21:43 | 3.557 GiB | 1,849 | 1,849 | 0 | 447,725 | 224,895 | 299,886 | 1,354 | 1,017,354 |
| 22:04 | 3.561 GiB | 1,738 | 1,735 | 0 | 452,690 | 234,645 | 299,886 | 1,255 | 1,262,418 |
| 22:24 | 3.561 GiB | 1,777 | 1,768 | 0 | 460,038 | 245,691 | 299,972 | 1,277 | 1,497,779 |
| 22:44 | 3.562 GiB | 1,827 | 1,818 | 0 | 466,082 | 257,350 | 299,899 | 1,897 | 1,731,445 |
| 23:04 | 3.580 GiB | 1,880 | 1,876 | 0 | 472,784 | 269,676 | 299,919 | 2,437 | 1,931,122 |

(33 readings in all, every 5 minutes; every other one shown. Trim history from the application log: each trim removed
81,000 to 129,000 flows and 59,000 to 91,000 profiles; signals started to be removed only from 21:53 local time,
15,000 to 23,000 per trim, all from wallets without a profile.)

### 3.3 Comparison

| Indicator | 3a alone (≈ 3 h) | 3b at 50 min | 3b final (2 h 42) |
|---|---:|---:|---:|
| Old generation used | 2.92 GB, rising | 1.66 GB | 1.88 GB (range 1.68-1.92 GB after 21:22), slowly rising |
| Live data after GC, slope after the caps bind | – | – | **≈ +67 MB/h** (21:22-23:04, 21 points) |
| Full GCs | 269 | 0 | **0** |
| Profiles | 650,496 | 299,781 | 299,919 (cap holds) |
| Flows | 961,581 | 437,035 | 472,784 (cap 400,000 + exempt, rising ≈ 18,000/h) |
| Signals | 301,021 | 135,271 | **269,676** (cap 150,000, rising ≈ 56,000/h) |
| Container memory | – | 3.53 GiB | 3.58 GiB of 4 GiB (89.5 %) |
| Restarts / OOM | 0 / no | 0 / no | 0 / no |

### 3.4 Class histogram (taken after wake-up, qualitative)

Taken on 2026-11-11 about 09:15, when the sampler finished after the machine woke up: the app had resumed and was
catching up again after 9 hours of suspension (Ethereum 6,690 and TRON 11,766 blocks behind). **Not comparable to the
run**; it shows the composition of the heap, not its size during the run. Largest classes:

| Rank | Class | Instances | MB |
|---:|---|---:|---:|
| 1 | `byte[]` (string contents) | 8,289,297 | 697 |
| 2 | `java.lang.String` | 8,282,890 | 199 |
| 3 | `HashMap$Node[]` (hash tables) | 1,510,569 | 144 |
| 4 | `LinkedHashMap$Entry` | 3,383,484 | 135 |
| 7 | `WalletProfileAccumulator$RecentKeys` (2 per profile) | 747,880 | 48 |
| 9 | `Signal$Evidence` | 1,144,531 | 37 |
| 12 | `WalletProfileAccumulator` | 373,940 | 33 |
| 13 / 25 | `RiskScore$Contribution` / `RiskScore` | 412,414 / 433,119 | 30 / 17 |
| 15 | `ValueFlow` | 556,574 | 27 |
| 22 | `Signal` | 291,360 | 19 |

Reading (an interpretation of the histogram, not a measurement per structure): strings and their contents are about
0.9 GB, half of the live data. The 3.4 million `LinkedHashMap` entries are mostly the per-profile de-duplication memory
of step 3b (event ids and transaction hashes, up to 4,096 of each per profile, held as full strings), which repeats ids
already held by the flow store. Flows themselves are small (27 MB of `ValueFlow` objects). 374,000 profiles at that
moment, above the 300,000 cap between two trims. This points to a cheaper representation of the de-duplication keys
(64-bit hashes instead of strings) as the largest saving available, ahead of the caps.

After wake-up the JVM also ran **one full GC** (G1 compaction, 1.8 s) during the renewed catch-up, plus the one forced
by the histogram itself; neither falls within the 20:22-23:04 window. Live data after GC at 09:15: 2.05 GB.

## 4. Memory stability analysis

Definitions: *heap used* (old generation, live data after GC), *heap committed* (reserved by the JVM, at its 3 GB
maximum since 21:02), *container memory* (`docker stats`, close to the process RSS), *container limit* (4 GB), *VM
available* (3.5 GB at 21:20).

- **No plateau.** Live data after GC oscillates (1,676 to 1,918 MB) around a rising trend of ≈ 67 MB/h once the caps
  bind. At that rate the remaining old-generation headroom (≈ 1.2 GB) would be consumed in roughly 18 hours of the same
  load, without any full GC before the end. This is slower growth, not a bound.
- **Container margin** to the 4 GB limit: about 420 MB outside the heap; non-heap use was stable during the run, but
  this margin is thin (a kernel kill would not show as a Java OutOfMemoryError).
- **GC**: no full GC; young and mixed collections only (3,038 young collections, 73.6 s in total, longest pause 0.17 s,
  at 21:20).
- **Trim cost**: 0.65 to 2.4 s per trim, rising with the signal count (the trim scans the signal store).

## 5. Caps audit

| Mechanism | Bounded? | Evidence |
|---|---|---|
| Profiles cap (300,000) | **Yes** | 299,132 to 300,039 at every trim from 20:42 |
| Flows cap (400,000) | Yes for evictable flows; **no for exempt ones** | 472,784 total and rising (5.2) |
| Signals cap (150,000) | **No** | 269,676 and rising (5.1) |
| Very active wallets (accumulators) | Yes per wallet | unit tests (`WalletProfileCompactionTest`); no hot-wallet growth visible in the run |
| Non-transfer event ids | Yes | 500,000 cap |
| Risk score history | Yes per address | 500 per address, addresses follow the profile cap |
| Watchlist, loaded histories | Kept, **not bounded** | see 5.2 |

### 5.1 Signals of profiled wallets

The cap pass only removes signals of wallets without a profile; a wallet that stays active keeps its profile and its
signals accumulate. Confirmed by the run: removals started only at 21:53 and only for wallets without a profile, while
the total kept rising by ≈ 56,000/h. Correction designed in `docs/P0_STEP_3_TECHNICAL_PLAN.md`, item 3 (per-wallet cap,
removal order with HIGH/CRITICAL last, metrics by severity).

### 5.2 Flows of exempt addresses (new finding)

Flows touching a watched address or an address whose history was loaded are never trimmed and are not counted by the
cap. The total minus the cap grew from ≈ 37,000 (21:02) to ≈ 73,000 (23:04). With an empty watchlist, the only exempt
addresses are the 65 address histories (corrected; first written as 10), among them 6 very active TRON addresses whose
history stopped at its cap (TRUNCATED). Every new flow of such an address is kept forever. **This is an inference from the totals; the
number of flows per exempt address was not measured.** It is the same question as the counterparty budgets of
ADR-0026 (client scope): exempt does not mean unbounded.

## 6. Impact on detection (unchanged from the draft, confirmed by the rates)

At ≈ 660,000 events per hour (catch-up) and with 400,000 evictable flows, unwatched wallets kept well under one hour of
flows during the run; at the steady state (≈ 200,000 flows/h) it would be about 2 hours. Detectors with longer windows
(structuring 24 h, circular flow 7 days, indirect exposure, unusual-amount baseline) are cut to that horizon for
unwatched wallets; dormant reactivation cannot fire; watched wallets and loaded histories keep everything. The impact
on detection quality has not been measured (see `docs/CHAINSENTINEL_CORE_MISSION_AUDIT.md` § 4.1).

## 7. Recovery after restart

Examined from the code and the tests, not exercised in production (a restart during the run would have invalidated it):
indexer checkpoints persisted (V8) and resume without gap up to 7,200 blocks; operator state, alerts and watchlist in
PostgreSQL; with the journal off, the in-memory working set starts empty after a restart. The reorganisation handling
committed later (`53566bd`) adds the limits listed in `docs/TECHNICAL_FOLLOW_UP.md` (TF-1). A controlled restart test
on the instance remains to be done with your authorisation.

## 8. Risks and limits of this run

- Catch-up load, not steady state; cut short by system sleep (2 h 42 of valid data instead of 3 h).
- Heap histogram taken after wake-up only (3.4): composition indicative, sizes not those of the run.
- Inference 5.2 not measured per address.
- Single run.

## 9. Verdict

**Improvement, to be confirmed.**

- Clear improvement over step 3a: 0 full GC instead of 269, profile cap exact, no restart; with step 3a alone the old
  generation gained 1.4 GB in its first 80 minutes and still about 0.3 GB/h at the end, reaching its limit; with step
  3b, about +67 MB/h once the caps bind.
- Not stable: memory still grows with uptime, the signal cap is exceeded by 80 % and rising, exempt addresses are
  unbounded, and the steady state was not observed. The absence of full GC alone does not make the run a success.

## 10. Recommended actions

1. **Fix the signal cap** (P0 item 3, already planned): per-wallet cap and removal order, metrics by severity.
2. **Bound the exempt addresses** (new): measure flows per exempt address first (metric
   `oci_working_set_exempt_flows{reason}`), then give watched wallets and loaded histories their own budget, in line with
   ADR-0026; until then, document that loading the history of a very active address costs memory for good.
3. **Second measurement**, after 1 and 2 and after the catch-up, with the machine kept awake (mains power, sleep
   disabled), at least 12 hours, with thresholds fixed in advance: live data after GC slope < 20 MB/h over the last 6
   hours; 0 full GC; container < 90 % of its limit; 0 restart; every cap respected at every trim (signals and exempt
   flows included); class histogram taken at the end.
4. **Compact the de-duplication keys of the profiles** (new, from 3.4): 64-bit hashes instead of full strings for the
   recent event ids and transaction hashes; measure the saving with a histogram before and after.
