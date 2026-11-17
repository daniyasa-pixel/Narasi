# Narasi: Hi-Res Recording & Offline AI Lab

Scope: native Android recording-quality validation for Samsung Galaxy S24.
Keep the old multitrack editor code in MainActivity; RecordingTestActivity is the temporary launcher.

## Recording profile negotiation
Try each real AudioRecord configuration in this order:
1. Mono 96,000 Hz / IEEE float 32-bit WAV (lossless)
2. Mono 48,000 Hz / IEEE float 32-bit WAV (lossless)
3. Mono 96,000 Hz / packed PCM 24-bit WAV
4. Mono 48,000 Hz / packed PCM 24-bit WAV
5. Mono 48,000 Hz / PCM 16-bit WAV
6. Mono 44,100 Hz / PCM 16-bit WAV

Prefer UNPROCESSED if the Android AudioManager reports support; then VOICE_RECOGNITION and MIC.
The UI must report the actual selected AudioRecord API configuration.
**Do not claim that 96 kHz/32-bit API capture proves physical ADC/microphone resolution.**
Device resampling or quantization may have occurred before app receives samples.

## Offline noise reduction
RNNoise v0.1.1 is vendored with its pre-trained RNN weights into the application native
library. No INTERNET permission, runtime download, model extraction, cloud API or account.
Upstream: https://github.com/xiph/rnnoise/tree/v0.1.1
License: src/main/cpp/rnnoise/COPYING (BSD-3-Clause).

RNNoise processing frames: 480 samples at 48 kHz, floating-point samples scaled to
RNNoise's conventional +/-32768 domain before native inference.
For a 96 kHz master, a windowed-sinc anti-alias filter converts the processing copy to
48 kHz; original recording remains completely unchanged. The same processing architecture
supports emergency 44.1 kHz fallback. AI output is 48 kHz 32-bit float WAV.

Post-NR mastering on processing copy: 70 Hz high-pass, conservative 2:1 dynamics at
-18 dBFS, ~0.6 dB makeup and -1 dBFS limiter. RNNoise blend defaults to 85%.
This is a first mastering preset to be validated by listening; no claim it outperforms
Samsung Voice Recorder until a controlled A/B test is performed.

## QA checklist on actual Samsung Galaxy S24
- [ ] Clean build via GitHub Actions
- [ ] Install APK (Android 14+ / Android 17) and allow microphone
- [ ] Record 15 s indoors quietly; check reported rate/source/bits, duration and WAV header
- [ ] Verify playback of original float/24-bit WAV through AudioTrack
- [ ] Run RNNoise with airplane mode ON; prove no download is required
- [ ] A/B through headphones; inspect musical noise and consonant clarity
- [ ] Compare using Samsung Voice Recorder with same mic distance and room conditions
- [ ] Test fan hum and road noise, plus quiet room, close/far mic placement
- [ ] Test wireless/Bluetooth and USB microphone routing where available
- [ ] Verify WAV export both originals and AI, import into editing software
- [ ] Interrupt recording with phone call and background switch; verify no corrupt originals
- [ ] Capture an extended recording; check battery, thermals and storage consumption
- [ ] Analyze clipping, noise floor, speech intelligibility and actual input path

No APK quality claim until tests are performed on-device.
