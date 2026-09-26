# Changelog — VoiceCast

English primary; Chinese mirror: [CHANGELOG.zh.md](CHANGELOG.zh.md) (keep both in sync, English wins on conflict).

## Unreleased

### Features

- Qwen3 decode language lock (voiceCast#49): the engine now constrains decoding to the session language via sherpa's native per-stream `language` option when the routed session has exactly ONE language bucket (`[voice] languages="en"` locks English) — open-multilingual decoding let a hotword-biased English utterance come back as Chinese text and cast a zh spell (reproduced in the lab: fulmen → 法门). `auto` is the default; `language_lock="off"` restores open decoding and any other value passes through verbatim as the language name (server-side override). Multi-bucket sessions deliberately stay open — a WRONG lock is worse than none (a zh utterance under English decodes as pinyin); report results now carry the ACTUAL decode language (empty = open) instead of the session config projection

- breaking: IPA CTC posterior calibration (R3/R5 port): vocabulary templates score forward-log-prob per token (lp/L) against a raw frame-sum null competitor, and multi-word templates concatenate instead of being dropped when the model vocab has no word-marker token — on the production-scale vocabulary this cuts non-spell false-accepts from 82% to 2.6% at the retuned default threshold (0.6 semantics are void under the new per-token scale; the forward threshold is the voicecast config key `[match] forwardThreshold` (0.10) and pre-existing per-spell threshold overrides must be re-tuned)

- Optional microphone noise suppression (`[client] noiseSuppression`, default off): streaming GTCRN speech enhancement (sherpa-onnx, 16 kHz native, ~523 KB model auto-downloaded through the catalog) applied to the recognition path only — what other players hear through Simple Voice Chat is unaffected; any denoiser failure degrades to clean passthrough
- models.json v2 catalog — the model catalog IS the engine list (voicecast#42): one model = one engine, the model name doubles as the engine id and the model directory name; per-language defaults follow declaration order (the first declared model supporting a language wins); selection accepts model names and two-letter language codes / common language names (`/voicecast engine en`, `zh`, `japanese`); addons can declare custom engine families via `properties.family` + the engine family SPI; the engine selection screen and `/voicecast engine list` are generated from the catalog

- Qwen3-ASR-0.6B offline multilingual engine (`qwen3-asr-0.6b-int8`, en/zh/ja/ko/yue/de/fr/es/ru) as the default utterance recognizer, with spell-alias hotword biasing (the session's language-routed trigger aliases, capped at 100 entries) and an automatic hotword-free second decode when a transcript comes back empty
- ZIPA IPA phoneme engine (`zipa-ipa`, ~70 MB int8) with the CTC template layer intact (margin rejection 0.02, null-path competitor): the posterior map and margin evidence are engine-internal - results carry the adjudicated `Decision` and the numbers stay reachable through `RecognitionDiagnostics` (`SpeechRecognizer.lastDiagnostics()`, semantic contract v2 below)

### Changes

- breaking: models.json schema v2 nests per-model `properties` (lang/type/options) and `source` (kind/urls/files) and drops the separate `engines` section — no migrations (v0 policy, AGENTS §3): files not in v2 shape are rewritten with the default catalog, and old engine ids (`sherpa-zh-en`, `sherpa-sensevoice`, `ipa-phonemes`, all vosk ids) no longer resolve — use catalog model names (`qwen3-asr-0.6b-int8`, `zipa-ipa`)
- breaking: Vosk removed and replaced by sherpa-onnx models (voicecast#42). The first sherpa lineup was itself replaced later in this same release (engine swap, see below) — the final catalog is `qwen3-asr-0.6b-int8` (default utterance recognizer), `zipa-ipa` (IPA phonemes) and `gtcrn-simple-denoiser` (microphone noise suppression). Models download on demand through the catalog
- breaking: config semantics simplified - `[client] engine` empty means "catalog default"; `[server] defaultEngine` accepts a model name or language code (empty = catalog default); `[engines] allowed` empty means every catalog model is allowed; no alias normalization or legacy imports remain in configs
- CTC margin rejection (S6 port, carried into contract v2): utterances whose top-two template posteriors finish within 0.02 of each other (lab calibration: FPR 0.3% at recall 42.8%) have every CTC score zeroed, so an ambiguous win cannot fire a borderline spell — the adjudicator sees the pre-margin gap and rules `AMBIGUOUS` when the suppressed top1 would have passed its forward threshold. Verbose logging (`/voicecast verbose`) prints the top1/top2 posterior values and their gap whenever the gate rejects — diagnostics only, the rule itself is unchanged
- The shared Qwen3-ASR native recognizer cache is LRU-bounded — at most 2 hotword-set sessions (~1.5-2.8 GB each) plus one pinned hotword-free instance — instead of accumulating a native session per distinct cast-mode word list: switching cast modes no longer grows native memory, evicted sessions are closed through sherpa (deferred while a decode is in flight on them), same-set reuse still never reloads, and the empty-transcript fallback never pays a reload
- Live partial-recognition preview (the grey italic line while holding PTT) is currently unavailable: the 0.5.0 engines are whole-utterance decoders that produce text only when you release PTT. The client preview pipeline stays wired and a future streaming engine can light it up again without protocol changes

- breaking: text NEAR (fuzzy alias similarity, Tier 3) is gated by two classical set-similarity filters before scoring (voiceCast#47 E, the "词数相差过大" rule): a **length filter** — the alias must span at least half the utterance's normalized characters — and an **overlap coefficient** for multi-word aliases (at least half the alias's own words present verbatim in the utterance). Previously the best-pair token average let every alias word find a loose buddy anywhere in a recited sentence, scoring unrelated triggers ~0.75-0.83 and false-firing them idle (real case: reciting "the pyre remembers my name" NEAR-matched an unrelated spell and skip-cast it). Half-spoken chants ("ign"→"ignis"), ASR mishearings ("falsome"→"falsum", 换蛋→虚影弹) and CJK single-word aliases are unaffected (single-token pairs skip the gates). The phoneme lane (Tier 4) keeps full-length keyword-in-utterance detection unchanged — a coverage-floor experiment flipped 23 fast-fixture cases that were all true positives (sentences containing the trigger word). Two c1b equivalence vectors amended to the new verdicts (v14 loses a cross-spell fuzzy runner-up; v42's negative-sentence case now rules AMBIGUOUS — the pinned NEAR ignis verdict was this exact bug class; wizardreal's byte-identical copy synced)

### Bugfixes

- breaking: IPA-class engines (zipa) no longer emit their greedy phoneme token string as `RecognitionResult.utteranceText` — that field is now always empty for them, and phonemes travel the `ipa()` field only (voicecast#47). The phoneme string previously entered the adjudicator's text tiers, where fuzzy matching against short trigger aliases produced confident wrong spells (`TEXT_NEAR` score 1.0 on unrelated spells); consumers that matched on `utteranceText()` of an ipa engine were matching phoneme soup and must switch to `ipa()` or the adjudicated spell/decision fields
- Disposing a speech session (quit, server stop) while another thread declared a cast mode or pushed a vocabulary no longer throws `RejectedExecutionException` at the game-thread caller (`VoiceCastServer.setCastMode` / vocabulary push): both entry points tolerate the torn-down session worker and silently no-op
- A session recognizer build seeds its fully routed vocabulary (cast mode ∩ engine languages) once before start instead of seeding a mode-blind language projection and re-setting the routed one afterwards — sherpa-based recognizers were constructing their hotword grammar twice per build, the first time with the wrong candidate set
- The streaming and offline recognizers passed a sample count where sherpa's `acceptWaveform` expects the sample **rate**, so incoming audio was time-stretched (each 200 ms production chunk stretched ~5x, bench files sped up) and transcripts came out truncated or missing entirely; both call sites now pass the real 16 kHz rate
- Joining a world threw `String too big` whenever the saved engine id exceeded 32 characters (v2 model names run up to 43+): the exception aborted the login-packet handler, which also killed fabric's client command dispatcher setup — every `/voicecast` client command then failed with `NullPointerException ... activeDispatcher is null` and client commands vanished from chat autocomplete. The engine-select channel now carries up to 256 characters and the join hook can no longer break sibling handlers
- The streaming recognizer failed to start with "Invalid OnlineRecognizerConfig: failed to create native OnlineRecognizer" whenever spell hotwords were active: `cjkchar+bpe` hotword encoding requires the model's `bpe.vocab`, which was never passed to sherpa. It is now wired (models.json `bpe_vocab` property), and a missing file degrades to hotword-free open-vocabulary decoding instead of failing the recognizer (voicecast#42)
- sherpa's config builders default to `debug=true` (dumping internal state); recognizer output is quiet unless dev verbosity is enabled (`gradlew runClient -PvoicecastVerbose=true` or `/voicecast verbose`, applied at recognizer (re)build)
- The model download HUD sat frozen on "999 MB" through ~100 MB stretches of large archives (GB display granularity); MB display is kept up to 10 GB so the counter visibly ticks
- Client-only commands (`/voicecast settings`, `verbose`, `debugwav`) were executable but missing from chat autocomplete: the server-side `/voicecast` admin tree shadowed the synced completion list; level-0 server stubs now merge the client-only children into the completion tree on both Fabric and Forge
- sherpa model downloads actually extract now: the downloader only unpacked `.zip` archives (the vosk format), so sherpa `tar.bz2` archives were left un-extracted and every load failed with "Downloaded model is missing expected files"; tar.bz2 / tgz / plain tar are handled too, and the nested top-level directory inside these archives is hoisted into the model root (voicecast#42)
- The model download HUD said "Downloading Vosk model" for sherpa downloads; it now says "Downloading model"
- A model download that fails mid-extraction no longer leaves a half-unpacked directory behind that would pass the installed-model checks forever and permanently hijack the engine: the target directory is cleared on any extraction/verification failure and re-downloaded, and already-installed model files are re-verified by content (sha256 where declared, size shape otherwise) before being trusted
- A model whose files pass their checks but fails to load natively (broken model files) no longer silently reloads at frame rate with zero player feedback: the session reports an ERROR state to the player and backs off (30 s doubling up to 5 min; re-selecting the engine retries immediately), and a FAILED engine re-tries downloads with a backoff instead of hammering the mirror every frame while someone holds PTT
- The session HUD shows a loading state during the (tens-of-seconds) native model load after the engine-level READY broadcast, instead of silently falling back to idle while the first utterance is swallowed
- A decision id from a mismatched server version no longer crashes the client's network thread: unknown ids degrade to "undecided" with a warning (same pattern as the state id guard)
- Client-side final-recognition events no longer report the spell id as the pronunciation id (the wire never carries it — unknown is reported instead), and partial results can no longer reach the final-event listeners addons hook
- An utterance that echoes the spell-alias list itself (recitation, issue #45) is dropped by the Qwen3 engine before it can match a spell verbatim: transcripts naming 3+ distinct aliases at once or running over 4x the longest alias are discarded (debug-logged); thresholds are deliberately conservative and stay tunable

- breaking: recognition lineup replaced (engine swap): removed the streaming bilingual zipformer (`sherpa-zipformer-bilingual-zh-en-int8`), both SenseVoice entries (`sherpa-sensevoice-small-int8`, `sherpa-sensevoice-full`) and the wav2vec2 IPA model (`wav2vec2-espeak-ipa`). The default catalog is now `qwen3-asr-0.6b-int8` -> `zipa-ipa` -> `gtcrn-simple-denoiser` (declaration order = per-language default). Old engine ids no longer resolve and no aliases ship (v0 policy, no migrations): re-select engines by the new catalog names; a 0.4.x `models.json` is rewritten with the new defaults on first load
- breaking: the IPA phoneme engine backend is ZIPA (zipa-small-crctc-ns-no-diacritics, Apache-2.0): zero diacritics in output, +31.9pp zh / +1.4pp en (same-route three-arm) trigger hit-rate over wav2vec2 in the engine-swap lab backtest, CPU RTF 0.014; heard-side normalization merges ZIPA's ASCII `r`/`g` onto the template symbols (`ɹ`/`ɡ`) and strips the word-boundary marker
- the phoneme cost table gained explicit default-cost rows covering the ZIPA symbol inventory (ð/æ/œ/ʁ/ø/ɳ/ɖ/ʈ/ʂ/ɻ/ʒ); out-of-table substitutions keep the flat 1.0 cost, and matching never fails on unseen symbols

### Modding/API

- breaking: builtin engine families are now `ipa` (ZIPA backend) and `sherpa-qwen3`; `sherpa-streaming` and `sherpa-sensevoice` are gone, and the offline family is no longer derived from `type=offline` — declare `properties.family` explicitly in models.json (addons register their own families unchanged)
- new: `ZipaPhonemeRecognizer`/`ZipaShared` (direct ONNX Runtime + a Java kaldi-fbank port, validated against the research pipeline fixtures) and `SherpaQwen3Recognizer` (offline `OfflineQwen3AsrModelConfig` + `setHotwords`, greedy) replace the previous recognizer classes; CTC template scoring semantics are unchanged

- breaking: `Pronunciation` briefly gained per-language alias buckets during this cycle; with the semantic contract v2 it is removed from the api entirely (no deprecated shim) — `SessionVocabulary` is the ONE vocabulary entry point (spell ids x language aliases x IPA templates x optional `ThresholdHint`)
- breaking: engine ids are catalog model names; deprecated `ENGINE_*` constants and the normalize alias table are gone - resolve against `ModelConfig.resolveModel(...)` (exact name, then language code by declaration order)
- new: engine family SPI - `EngineFamilies.register(type, RecognizerFactory)` (`com.theo.voicecast.api.engine`) lets addon mods register their own recognizer families, selectable through `properties.family` in models.json; built-in families: `ipa`, `sherpa-qwen3` (voicecast#19)
- new: casting-time vocabulary routing API (D-15 four-mode decision, wizardreal#30): `CastMode` (OPEN / CHANT_CONFIRM / PRACTICE_CONFIRM / GRAY_NARROW) plus `VoiceCastServer.setCastMode(player, mode, spellIds)` let game-side integrations declare what a player's recognizer grammar hears — OPEN = the full word list (the mode id exists so integrations can declare free casting explicitly), CHANT_CONFIRM = the declared spell's every alias (ladder chant in progress), PRACTICE_CONFIRM = the same shape for practice surfaces (M4), GRAY_NARROW = declared + top-3 confusion neighbors (shipped asset `assets/voicecast/confusion_neighbors.tsv`, derived from the M2E red/yellow ledger); per-mode CTC forward thresholds ship as `assets/voicecast/mode_thresholds.tsv` (first batch mirrors the shipped 0.10 constant; recalibration rows land as data-only edits). Engines without a language (zipa-ipa, noop) are never mode-routed — the IPA line stays full-vocabulary — and sessions without any declaration keep the exact pre-0.5.0 routing
- OPEN stays the full word list (supervisor ruling on the P30 re-verification): the 0.5.0 candidate-set definition "trigger + release aliases" was reverted after the production-semantics re-verification (S9ProductionRematch harness) showed the SpellMatcher's Phonetics layer re-shuffles rather than removes false triggers under a narrowed OPEN set — the four-mode mechanism itself is unchanged

- breaking: semantic recognition contract v2 (engine-swap C1b) — `RecognitionResult` is now `record(utteranceText, ipa, language, decision, spellId, pronId, score, alternatives, startMs, endMs)` where voicecast itself adjudicates the utterance: `Decision` = `EXACT / NEAR / AMBIGUOUS / REJECTED` (fusion priority: text EXACT > zipa EXACT > text NEAR > zipa NEAR > AMBIGUOUS/REJECTED), `spellId`/`pronId` name the winning vocabulary entry and `alternatives` carries up to 3 runner-ups. The engine-internal CTC posterior map and the `ctcPresent` flag left the result contract — they stay reachable through the standalone `RecognitionDiagnostics` accessor (`SpeechRecognizer.lastDiagnostics()`, diagnostics only, never a decision input). Partial results carry a null decision (HUD readout only). Cross-repo behavior is pinned by the shared equivalence vectors (`c1b_vectors.json`, voicecast + wizardreal tests, contract doc `docs/ref/voicecast-recognition-contract.md`)
- breaking: the vocabulary push is `SessionVocabulary` (the ONE entry point: spell ids x language aliases x IPA templates x optional `ThresholdHint`) — `Pronunciation` and the shared `IpaText` utility are gone from the api (v0 hard cut, no deprecated shims); engines derive their own shapes (ZIPA token templates, qwen3 hotwords, matcher surfaces) internally, and no engine-shaped payload crosses the mod boundary
- breaking: matcher/threshold ownership moved into voicecast (`com.theo.voicecast.match`: `SpellMatcher`, `PhonemeMatcher` + the confusion-cost asset `assets/voicecast/phoneme_costs.tsv`, `Phonetics`, `IpaText`, `UtteranceAdjudicator`) — engine-calibration defaults are voicecast config now (`[match] forwardThreshold` 0.10 / `phonemeThreshold` 0.6 / `textThreshold` 0.65 / `ctcMargin` 0.02, travelling via the new `Calibration` component on `SpeechOptions`); game-side tuning (per-spell threshold, per-mode calibration rows, reject levels) crosses as per-entry `ThresholdHint` data (a component above 1.0 disables that tier for the entry), not as consumer-side logic
- `SpeechRecognizer.setVocabulary` takes `SessionVocabulary`; a `lastDiagnostics()` default method exposes the engine-internal numbers of the last adjudication

### Protocol

- The `audio/select` frame (client → server engine selection) carries engine ids of up to 256 characters — v2 model names exceed the previous 32-character limit; client and server already need matching versions
- breaking: the S2C transcript packet now carries the adjudicated decision (ordinal, -1 = partial) and the winning spell id next to the score, so the client HUD reports the same verdict the server ruled; client and server must be on matching versions (C1b)

### Infrastructure

- Engine classes (IpaShared/IpaPhonemeRecognizer/VoskTextRecognizer/AbstractBufferedRecognizer/MiniJson/NoopRecognizer) no longer route logging through the mod class - they carry their own slf4j loggers, so the engine runs standalone (voicecast fat jar) for the wizardreal ipa backtest tools; NoiseSuppression and ModelConfig got the same treatment
- Dev-only testing mods moved out of gradle: release jars are pre-downloaded under workspace `resources/devmods/<loader>/` and wired from `manifest.txt` (fabric: hardlinked into the run mods folder; forge: file dependency so Loom remaps the SRG jar; Forge port of Carpet stays blocked, voicecast#38); voice-model fact source moved to `resources/models/`
- Dev model seeding is manifest-driven: workspace `resources/models/manifest.txt` lists the model dirs hardlinked into the run dirs (one per line, commented-out = disabled; absent manifest seeds everything) - the vosk models are commented out there in step with the vosk removal
- The vosk dependency (compileOnly + fat-jar bundling) is gone from the build; tar extraction uses the commons-compress the Minecraft runtime already ships (compile-only, never bundled)

## 0.3.2 — 2026-09-02

### Changes

- Engine ids unified to `vosk-<language>`: `vosk-en`, `vosk-cn`, `vosk-jp`, `vosk-kr`, `ipa-phonemes`; the `vosk-text` id is retired (plain English = `vosk-en`), and the full engine set is registered out of the box
- Simple Voice Chat coexistence is share-only: the experimental `defer` mode was removed; configuring `svcCoexistence = "defer"` falls back to `share` with a one-time warning (voicecast#27)
- Removed dead server config key `[server] opusBitrate` (voicecast#3)

### Bugfixes

- Dev `runServer` and `runClient` now use separate run directories (`run-server/`), so they can run concurrently on Windows
- Voice models are auto-seeded into run directories as hard links (zero copy; deleting a link never touches the workspace copy)

### Modding/API

- breaking: engine id scheme is now `vosk-<lang>` — configs and commands referencing `vosk-text` must switch to `vosk-en`

### Packaging

- IPA model ships q4 ONNX only (the float32 fallback was dropped); full-platform ONNX Runtime natives retained for dedicated servers
- `config/` gitignore rule scoped to run directories

### Infrastructure

- GitHub Actions build+test workflow (tag/PR triggers, foojay toolchain resolver); new issues auto-added to the Be a Real Wizard project; issue templates (bug/feature/config)
- Wiki split into VoiceCast-specific bilingual pages

## 0.3.1 — 2026-09-01

### Features

- Simple Voice Chat coexistence (M7b): VoiceCast always shares the microphone device; when SVC transmitted within the last 300 ms, a throttled info line notes the overlap (never blocks or mutes SVC)

### Changes

- Dead config fields removed (M7b W4)

### Modding/API

- SVC plugin registers loader-natively (Fabric entrypoint key `voicechat` / Forge `@ForgeVoicechatPlugin` annotation scan) — never ServiceLoader; the plugin class never instantiates when SVC is absent

### Packaging

- voicechat-api 2.6.20 is compileOnly and never bundled; `verifyFatJarPolicy` guards packaging rules (module-info stripping, JNA policy, Concentus presence)

### Infrastructure

- Repo split baseline housekeeping: GitHub issue templates, add-to-project workflow

## 0.3.0 — 2026-09-01 (workspace split baseline)

### Features

- Offline voice casting foundation: push-to-talk capture, energy VAD, streaming Vosk recognition (en/cn/ja/ko models), wav2vec2-espeak IPA phoneme engine, spell matching, on-screen transcript HUD with mic level meter
- Server-side recognition: clients stream Opus-encoded audio to the server and download no models (q4 IPA model ~230 MB lives on the server)
- Model manager with SHA-verified downloads and hf-mirror fallback
- Persistent client/server config (engine choice, HUD anchor, VAD thresholds, verbose logging)

### Modding/API

- Stable addon SPI `com.theo.voicecast.api`: `SpeechRecognizer`, `RecognizerRegistry`, `Pronunciation`, `VoiceCastEvents` (partial/final/state/audio-level events); public API free of vosk/JNA/ORT types

### Protocol

- `voicecast:audio` channel: Opus (Concentus) frame streaming c2s, state/transcript s2c; server-authoritative recognition sessions per player

### Packaging

- Architectury dual-loader (Fabric + Forge); JNA never relocated or shaded; Vosk/ORT natives bundled for win/linux/macos
