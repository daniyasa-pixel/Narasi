# Narasi — Voice Over Studio

Aplikasi Android native (Kotlin) untuk produksi voice over dengan editor timeline multi-track, teleprompter, pemrosesan suara offline, sound effects, dan ekspor audio + subtitle.

## Status
Tahap awal: spesifikasi dan rancangan UI disepakati; implementasi Kotlin dan pengujian Android belum dimulai.

## Teknologi
- Kotlin, Jetpack Compose, Material 3
- Android NDK/JNI untuk RNNoise (AI noise reduction offline)
- Audio processing lokal; rekaman asli tidak ditimpa (non-destructive)
- UI prototipe HTML digunakan sebagai referensi, bukan WebView utama

## Rancangan UI
- Minimalis, gelap, nyaman dipakai di HP
- Tombol Rekam dan Play berada di bawah, mudah dijangkau
- Teleprompter dapat dibuka untuk latihan dan tampil otomatis saat merekam; teks bergulir ke atas dengan pengaturan kecepatan
- Subtitle preview mengikuti posisi playback
- Timeline bisa digeser dan diperbesar, klip drag, trim, split, delete, undo/redo, magnet ON/OFF
- Toolbar editing horizontal dengan ikon (misalnya gunting untuk split)
- Tombol + tanpa label teks untuk menambah track

## Jenis track dinamis
- Voice Over (VO 1, VO 2, dst.)
- Background Music
- Sound Effects
- Subtitle/SRT

Satu track dapat berisi beberapa klip. Rekaman masuk ke track VO yang dipilih; jika belum ada, aplikasi dapat membuat track baru.

## Voice Enhance
- RNNoise untuk noise reduction, offline
- Bass/Warmth EQ, Crisp/Clarity EQ, Compressor, Auto Level
- Preset Natural, Studio, Deep Bass, Crispy
- Before/After; pemrosesan hanya memengaruhi VO, bukan musik
- Hasil diproses tanpa menimpa rekaman asli

## Sound Effects
Library bawaan dengan kategori Whoosh, Chime, Click/Pop, Impact, Digital, Alert, Riser, Camera; pengguna dapat impor dan menyimpan SFX sendiri. Gunakan efek yang dibuat sendiri atau yang berlisensi untuk distribusi dalam aplikasi.

## Output final
- `voiceover_final.mp3` — campuran VO hasil edit/enhance, musik, dan SFX
- `subtitle_final.srt` — subtitle bertimestamp untuk impor ke editor video seperti CapCut

## Tahapan
1. Fondasi proyek Android Kotlin + UI Compose
2. Rekaman, impor, teleprompter, playback, dan timeline
3. Editing klip multi-track dan penyimpanan proyek lokal
4. RNNoise, EQ, compressor, auto level, serta library SFX
5. Subtitle otomatis, penyuntingan dan sinkronisasi
6. Ekspor MP3/SRT dan pengujian di Android

Catatan: belum ada klaim fitur sudah berjalan. Implementasi dilakukan bertahap dan diuji di perangkat Android.
