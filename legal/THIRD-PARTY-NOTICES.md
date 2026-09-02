# Third-Party Notices

Components **bundled** inside the distributable jars of this project. Each is
used unmodified under its own license. The non-bundled runtime dependencies
(Fabric Loader/API, Forge, Architectury) and dev-only tooling are declared in
each subproject's build scripts and mod manifests.

## Bundled into the voicecast jars

### Vosk 0.3.45 — Java bindings + `libvosk` native libraries

- License: Apache-2.0 (Vosk and the Kaldi toolkit it builds on)
- Upstream: <https://github.com/alphacephei/vosk-api>, <https://github.com/kaldi-asr/kaldi>
- Used by: the `vosk-en` engine (server-side offline word recognition).
- Note: the Apache-2.0 license text is distributed by the upstream project;
  a copy can be obtained from the upstream repository (`LICENSE` file).

### ONNX Runtime 1.19.2 — Java API + all-platform native libraries

- License: MIT — Copyright (c) Microsoft Corporation
- Upstream: <https://onnxruntime.ai/>, <https://github.com/microsoft/onnxruntime>
- Used by: the `ipa-phonemes` engine (wav2vec2-espeak phoneme inference on CPU).
- MIT text:

> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions: The above copyright
> notice and this permission notice shall be included in all copies or
> substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS",
> WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
> TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
> FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
> TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
> THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### Concentus 1.0.2 (jaredmdobson fork) — pure-Java Opus codec

- License: MIT
- Upstream: <https://github.com/jaredmdobson/concentus>
- Used by: Opus encoding/decoding for the client→server audio channel.
- MIT text: same as above (copyright its respective authors).

### JNA 5.12.1 — Java Native Access

- License: dual-licensed, choose one: Apache-2.0 OR LGPL-2.1-or-later
- Upstream: <https://github.com/java-native-access/jna>
- Used by: Vosk native binding. JiJ'd into the Fabric jar; on Forge it is
  provided at runtime by Architectury/Forge and is deliberately NOT bundled.
- Note: JNA is kept at its original `com.sun.jna` package (never relocated) —
  its native `jnidispatch` binds to the original symbol names.

## Related but NOT bundled

- **Speech models** (`vosk-model-small-*` zips, `wav2vec2-lv-60-espeak-cv-ft`
  ONNX q4/float32 weights + vocab): downloaded at runtime into
  `config/voicecast/models/`, never shipped inside the jars. Apache-2.0
  (Vosk models, wav2vec2, ONNX conversion) / CC0 (Mozilla Common Voice data).
- **Opus**: BSD-3-Clause (specification / reference implementation that
  Concentus ports) — <https://opus-codec.org/>.
