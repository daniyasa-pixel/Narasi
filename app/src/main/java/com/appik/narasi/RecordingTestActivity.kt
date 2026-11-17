package com.appik.narasi

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

private val Dark = Color(0xFF10131D)
private val Surface = Color(0xFF1C2230)
private val Accent = Color(0xFFB9A3FF)
private val SecondaryText = Color(0xFFB7BECD)

class RecordingTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Dark, primary = Accent, surface = Surface
            )) {
                RecordingTestScreen()
            }
        }
    }
}

@Composable
private fun RecordingTestScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { WavAudioRecorder(context.applicationContext) }
    val player = remember { WavAudioPlayer() }
    val store = remember { context.getSharedPreferences("narasi_hires_lab", 0) }
    val folder = remember { File(context.filesDir, "masters").apply { mkdirs() } }

    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var original by remember {
        mutableStateOf(store.getString("original", null)?.let(::File)?.takeIf(File::isFile))
    }
    var enhanced by remember {
        mutableStateOf(store.getString("enhanced", null)?.let(::File)?.takeIf(File::isFile))
    }
    var captureInfo by remember {
        mutableStateOf(store.getString("capture_info", "Belum ada rekaman") ?: "Belum ada rekaman")
    }
    var message by remember {
        mutableStateOf("Master lossless. AI RNNoise sudah terpasang di APK dan bekerja offline.")
    }
    var durationMs by remember { mutableLongStateOf(0L) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var selectedPlayingPath by remember { mutableStateOf<String?>(null) }
    var nrPercent by remember { mutableFloatStateOf(85f) }
    var pendingExport by remember { mutableStateOf<File?>(null) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { destination: Uri? ->
        val source = pendingExport
        pendingExport = null
        if (destination != null && source?.isFile == true) {
            scope.launch {
                val exported = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(destination)?.use { output ->
                            FileInputStream(source).use { input -> input.copyTo(output) }
                        } ?: error("Tidak bisa membuka lokasi penyimpanan")
                    }
                }
                message = if (exported.isSuccess) "WAV berhasil diekspor." else
                    "Ekspor gagal: " + exported.exceptionOrNull()?.message
            }
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        message = if (allowed) "Mikrofon diizinkan. Ketuk Mulai Rekam." else
            "Izin mikrofon diperlukan untuk merekam."
    }

    fun startCapture() {
        if (busy || recording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        player.stop()
        selectedPlayingPath = null
        try {
            val file = File(folder, "narasi_master_" + System.currentTimeMillis() + ".wav")
            recorder.start(file)
            recordingStartedAt = SystemClock.elapsedRealtime()
            recording = true
            durationMs = 0
            val profile = recorder.activeFormat
            captureInfo = (profile?.label ?: "WAV") + " · " +
                (profile?.sourceLabel ?: "MIC") + " · Mono"
            message = "Sedang merekam tanpa kompresi. Jaga jarak mikrofon dan hindari clipping."
        } catch (e: Exception) {
            message = "Mikrofon gagal: " + (e.message ?: "Tidak diketahui")
        }
    }

    fun stopCapture() {
        if (!recording || busy) return
        recording = false
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { recorder.stop() }
            }
            result.onSuccess {
                original = it.file
                enhanced = null
                captureInfo = it.format.label + " · " + it.format.sourceLabel + " · Mono"
                store.edit().putString("original", it.file.absolutePath)
                    .remove("enhanced")
                    .putString("capture_info", captureInfo).apply()
                message = "Master asli tersimpan (" + (it.durationMs / 1000) +
                    " detik). Putar suara asli sebelum menjalankan AI."
            }.onFailure {
                message = "Perekaman gagal disimpan: " + (it.message ?: "I/O")
            }
            busy = false
        }
    }

    fun startEnhance() {
        val source = original ?: return
        if (busy || recording) return
        player.stop()
        selectedPlayingPath = null
        busy = true
        message = "RNNoise + mastering ringan sedang memproses secara offline..."
        val target = File(folder, "narasi_ai_" + System.currentTimeMillis() + ".wav")
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    OfflineVoiceEnhancer.process(source, target, nrPercent / 100f)
                }
            }
            result.onSuccess {
                enhanced = it
                store.edit().putString("enhanced", it.absolutePath).apply()
                message = "AI selesai. Bandingkan langsung dua file dan ekspor jika cocok."
            }.onFailure {
                message = "Proses AI gagal: " + (it.message ?: "RNNoise error")
            }
            busy = false
        }
    }

    fun playOrStop(file: File?) {
        if (file?.isFile != true || recording || busy) return
        if (selectedPlayingPath == file.absolutePath) {
            player.stop()
            selectedPlayingPath = null
            return
        }
        selectedPlayingPath = file.absolutePath
        player.play(file) { failure ->
            scope.launch {
                if (selectedPlayingPath == file.absolutePath) selectedPlayingPath = null
                if (failure != null) message = "Playback gagal: " + failure.message
            }
        }
    }

    fun export(file: File?, name: String) {
        if (file?.isFile != true || recording || busy) return
        pendingExport = file
        exportPicker.launch("narasi_" + name + "_" + System.currentTimeMillis() + ".wav")
    }

    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            recorder.cancel()
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            durationMs = SystemClock.elapsedRealtime() - recordingStartedAt
            delay(100)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Dark).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Narasi", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Voice Over Studio · Hi-Res Recording Lab",
            fontSize = 12.sp, color = SecondaryText)

        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Surface, RoundedCornerShape(18.dp))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Mic, contentDescription = null,
                tint = if (recording) Color(0xFFFF7485) else Accent,
                modifier = Modifier.size(44.dp))
            val total = durationMs / 1000
            Text(
                "%02d:%02d".format(total / 60, total % 60),
                fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
            Text(
                captureInfo, color = SecondaryText, fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (recording) Color(0xFFD84C61) else Accent,
                    contentColor = Dark
                ),
                onClick = { if (recording) stopCapture() else startCapture() }
            ) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null)
                Spacer(Modifier.width(8.dp))
                Text(if (recording) "Berhenti & Simpan Master" else "Mulai Rekam Hi-Res")
            }
            Text("Asli disimpan tanpa AI dan tanpa kompresi.",
                color = SecondaryText, fontSize = 11.sp)
        }

        Text("Dengarkan A/B", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = original != null && !recording && !busy,
                onClick = { playOrStop(original) }
            ) {
                Icon(
                    if (selectedPlayingPath == original?.absolutePath)
                        Icons.Default.Stop else Icons.Default.PlayArrow,
                    null
                )
                Spacer(Modifier.width(4.dp))
                Text("Suara Asli", fontSize = 12.sp)
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = enhanced != null && !recording && !busy,
                onClick = { playOrStop(enhanced) }
            ) {
                Icon(
                    if (selectedPlayingPath == enhanced?.absolutePath)
                        Icons.Default.Stop else Icons.Default.PlayArrow,
                    null
                )
                Spacer(Modifier.width(4.dp))
                Text("Suara AI", fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Surface, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = Accent)
                Spacer(Modifier.width(8.dp))
                Text("RNNoise · Offline", color = Color.White,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Mesin AI dan bobot model sudah di dalam APK. " +
                    "Tidak perlu koneksi, akun, atau download.",
                color = SecondaryText, fontSize = 12.sp, lineHeight = 17.sp
            )
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Kekuatan Noise Reduction", color = SecondaryText, fontSize = 12.sp)
                Text(nrPercent.toInt().toString() + "%",
                    color = Accent, fontSize = 12.sp)
            }
            Slider(
                value = nrPercent,
                onValueChange = { nrPercent = it },
                valueRange = 0f..100f,
                enabled = !busy
            )
            Button(
                onClick = { startEnhance() },
                enabled = original != null && !recording && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text("Proses dengan RNNoise")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Hasil AI: WAV 48 kHz / 32-bit float · high-pass 70 Hz · " +
                "compressor ringan · limiter -1 dBFS.",
                color = SecondaryText, fontSize = 11.sp, lineHeight = 17.sp)
        }

        Text(message, color = SecondaryText, fontSize = 12.sp, lineHeight = 18.sp)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { export(original, "asli") },
                enabled = original != null && !busy && !recording,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ekspor Asli", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { export(enhanced, "ai") },
                enabled = enhanced != null && !busy && !recording,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ekspor AI", fontSize = 12.sp)
            }
        }

        Text("Profil perekaman mengikuti kemampuan jalur audio Android. " +
            "Angka besar bukan jaminan resolusi fisik mikrofon. " +
            "Bandingkan dengan Voice Recorder Samsung pada lokasi dan jarak yang sama.",
            color = SecondaryText, fontSize = 11.sp, lineHeight = 17.sp)
    }
}
