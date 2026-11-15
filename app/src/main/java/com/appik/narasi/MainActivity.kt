package com.appik.narasi

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

private val Bg = Color(0xFF11141C)
private val Panel = Color(0xFF1D2230)
private val Purple = Color(0xFFB8A0FF)
private val Muted = Color(0xFFAAB3C5)

data class Clip(
    val id: Int,
    val name: String,
    val audioPath: String? = null,
    val durationMs: Long = 0L
)

data class Track(
    val id: Int,
    val kind: String, // "VO", "Music", "SFX", "Subtitle"
    val name: String,
    val clips: androidx.compose.runtime.snapshots.SnapshotStateList<Clip> = mutableStateListOf()
)

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Izin mikrofon diperlukan untuk merekam audio", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Bg, surface = Panel)) {
                StudioScreen(
                    onCheckPermission = {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            false
                        } else {
                            true
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun StudioScreen(onCheckPermission: () -> Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tracks = remember {
        mutableStateListOf(
            Track(1, "VO", "VO 1"),
            Track(2, "Music", "Music 1"),
            Track(3, "Subtitle", "SRT 1")
        )
    }
    var nextId by remember { mutableIntStateOf(4) }
    var nextClipId by remember { mutableIntStateOf(4) }
    var script by remember { mutableStateOf("Selamat datang di Yasa Narasi Voice Over Studio.\nRekam suara Anda dengan jelas dan profesional.") }
    
    // States
    var recording by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var enhance by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf("Studio") }
    var speed by remember { mutableFloatStateOf(2f) }
    var countdown by remember { mutableIntStateOf(0) }
    var teleprompterMode by remember { mutableStateOf(false) }
    var practiceMode by remember { mutableStateOf(false) }
    
    // Audio recorders & players
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentAudioPath by remember { mutableStateOf<String?>(null) }
    var recordDurationSeconds by remember { mutableIntStateOf(0) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    
    // Timer for recording
    LaunchedEffect(recording) {
        if (recording) {
            recordDurationSeconds = 0
            while (recording) {
                delay(1000L)
                recordDurationSeconds++
            }
        }
    }

    // Function to start recording
    val startRecordingAction = {
        if (onCheckPermission()) {
            val file = File(context.filesDir, "vo_${System.currentTimeMillis()}.m4a")
            try {
                val recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                currentAudioPath = file.absolutePath
                mediaRecorder = recorder
                recordingStartedAt = android.os.SystemClock.elapsedRealtime()
                recording = true
            } catch (e: Exception) {
                file.delete()
                currentAudioPath = null
                mediaRecorder?.release()
                mediaRecorder = null
                e.printStackTrace()
                Toast.makeText(context, "Gagal memulai rekaman: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Save only successfully finalized audio; never add corrupt files to the timeline.
    val stopRecordingAction = {
        val path = currentAudioPath
        val elapsed = if (recordingStartedAt > 0L) android.os.SystemClock.elapsedRealtime() - recordingStartedAt else 0L
        var saved = false
        try {
            mediaRecorder?.stop()
            saved = path != null && File(path).exists() && File(path).length() > 0L
        } catch (e: Exception) {
            Toast.makeText(context, "Rekaman gagal disimpan", Toast.LENGTH_SHORT).show()
        } finally {
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            recording = false
            recordingStartedAt = 0L
            currentAudioPath = null
        }
        if (saved && path != null) {
            var voTrack = tracks.find { it.kind == "VO" }
            if (voTrack == null) {
                voTrack = Track(nextId++, "VO", "VO 1")
                tracks.add(0, voTrack)
            }
            val clipName = "VO Rekaman ${voTrack.clips.size + 1}"
            voTrack.clips.add(Clip(nextClipId++, clipName, path, elapsed))
            Toast.makeText(context, "Rekaman disimpan: $clipName", Toast.LENGTH_SHORT).show()
        } else if (path != null) {
            File(path).delete()
        }
    }

    // Function to play latest audio
    val togglePlayback = {
        val lastVoClip = tracks.find { it.kind == "VO" }?.clips?.lastOrNull { it.audioPath != null }
        val path = lastVoClip?.audioPath
        if (path != null && File(path).exists()) {
            if (playing) {
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                } catch (_: Exception) {}
                mediaPlayer = null
                playing = false
            } else {
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(path)
                        prepare()
                        start()
                        setOnCompletionListener {
                            playing = false
                        }
                    }
                    playing = true
                    Toast.makeText(context, "Memutar rekaman...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Gagal memutar audio: ${e.message}", Toast.LENGTH_SHORT).show()
                    playing = false
                }
            }
        } else {
            Toast.makeText(context, "Belum ada file rekaman audio untuk diputar", Toast.LENGTH_SHORT).show()
        }
    }

    // Teleprompter / Practice Overlay
    if (teleprompterMode || practiceMode || countdown > 0) {
        Box(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (practiceMode) "📖 Latihan Teleprompter" else "🎙 Merekam Teleprompter", color = Purple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    IconButton(onClick = {
                        if (recording) stopRecordingAction()
                        teleprompterMode = false
                        practiceMode = false
                        countdown = 0
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = Color.White)
                    }
                }
                
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    if (countdown > 0) {
                        LaunchedEffect(countdown) {
                            delay(1000L)
                            if (countdown > 1) {
                                countdown--
                            } else {
                                countdown = 0
                                if (!practiceMode) {
                                    startRecordingAction()
                                }
                            }
                        }
                        Text("$countdown", color = Purple, fontSize = 72.sp, fontWeight = FontWeight.Bold)
                    } else {
                        val scrollState = rememberScrollState()
                        LaunchedEffect(recording, practiceMode) {
                            if (recording || practiceMode) {
                                while (true) {
                                    delay(50L)
                                    scrollState.scrollBy(speed * 1.5f)
                                }
                            }
                        }
                        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(100.dp))
                            Text(script, color = Color.White, fontSize = 24.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(300.dp))
                        }
                    }
                }

                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (recording) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color.Red))
                            Text("Merekam: 00:${String.format("%02d", recordDurationSeconds)}", color = Color.Red, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("Kecepatan Teks: ${speed.toInt()}x", color = Muted, fontSize = 12.sp)
                    Slider(value = speed, onValueChange = { speed = it }, valueRange = 1f..5f, modifier = Modifier.fillMaxWidth(0.7f))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (!practiceMode && countdown == 0 && recording) {
                            Button(
                                onClick = {
                                    if (recording) stopRecordingAction()
                                    teleprompterMode = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC63850))
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Selesai & Simpan")
                            }
                        } else if (practiceMode) {
                            Button(onClick = { practiceMode = false }) {
                                Text("Keluar Latihan")
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // Main Compact Studio Screen
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(8.dp)) {
        // Top Bar (Super compact)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🎙 Narasi", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                OutlinedButton(
                    onClick = { practiceMode = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Teleprompter", fontSize = 11.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = !enhance,
                    onClick = { enhance = false },
                    label = { Text("Editor", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = enhance,
                    onClick = { enhance = true },
                    label = { Text("Enhance", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (enhance) {
            // Enhance Menu
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(8.dp)) {
                Text("Preset Suara", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Natural", "Studio", "Deep Bass", "Crispy").forEach { p ->
                        FilterChip(
                            selected = preset == p,
                            onClick = { preset = p },
                            label = { Text(p, fontSize = 11.sp) },
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("AI Noise Reduction (RNNoise — Belum Tersedia di Tahap Ini)", color = Muted, fontSize = 11.sp)
                Text("EQ & Compressor (Belum Tersedia di Tahap Ini)", color = Muted, fontSize = 11.sp)
            }
        } else {
            Column(Modifier.weight(1f)) {
                // Script / Teleprompter mini card
                Row(
                    Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("NASKAH / TELEPROMPTER", color = Purple, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        BasicTextField(
                            value = script,
                            onValueChange = { script = it },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 54.dp)
                        )
                    }
                    IconButton(onClick = { if (onCheckPermission()) { teleprompterMode = true; countdown = 3 } }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Mic, contentDescription = "Mulai Rekam Teleprompter", tint = Purple)
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Compact Subtitle Preview
                Box(Modifier.fillMaxWidth().background(Color(0xFF080B12), RoundedCornerShape(6.dp)).padding(6.dp), contentAlignment = Alignment.Center) {
                    Text("Subtitle Preview: Selamat datang di Yasa Narasi", color = Muted, fontSize = 10.sp)
                }

                Spacer(Modifier.height(6.dp))

                // Timeline Header & Controls
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Timeline (${tracks.size} Track)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { addMenu = true }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.AddCircle, contentDescription = "Tambah Track", tint = Purple)
                            }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                listOf("VO" to "Voice Over", "Music" to "Background Music", "SFX" to "Sound Effect", "Subtitle" to "Subtitle").forEach { (kind, label) ->
                                    DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = {
                                        val count = tracks.count { it.kind == kind } + 1
                                        tracks.add(Track(nextId++, kind, when (kind) { "VO" -> "VO $count"; "Music" -> "Music $count"; "SFX" -> "SFX $count"; else -> "SRT $count" }, mutableStateListOf()))
                                        addMenu = false
                                    })
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Compact Multi-Track Timeline Rows
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).background(Panel, RoundedCornerShape(8.dp)).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        Text("00:00        00:05        00:10        00:15", color = Muted, fontSize = 9.sp)
                    }
                    items(tracks, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF141822), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {

                            
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (t.clips.isEmpty()) {
                                    Text(t.name, color = Muted, fontSize = 11.sp)
                                } else {
                                    t.clips.forEach { clip ->
                                        Box(
                                            Modifier
                                                .height(26.dp)
                                                .background(
                                                    when (t.kind) {
                                                        "VO" -> Color(0xFF6650A4)
                                                        "Music" -> Color(0xFF176B64)
                                                        "SFX" -> Color(0xFF8A5B22)
                                                        else -> Color(0xFF865D26)
                                                    },
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 8.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("${t.name} · ${clip.name}", color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Clip)
                                                IconButton(
                                                    onClick = { t.clips.remove(clip) },
                                                    modifier = Modifier.size(16.dp)
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = "Hapus Klip", tint = Color.LightGray, modifier = Modifier.size(10.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            IconButton(onClick = { tracks.remove(t) }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Hapus Track", tint = Color.DarkGray, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Compact Editing Toolbar (with disabled state / toast for unimpl features)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        Icons.Default.ContentCut to "Split (Belum tersedia)",
                        Icons.Default.Delete to "Hapus Klip",
                        Icons.Default.ContentCopy to "Duplikat (Belum tersedia)",
                        Icons.Default.VolumeUp to "Volume (Belum tersedia)",
                        Icons.Default.GraphicEq to "Fade (Belum tersedia)",
                        Icons.Default.Tune to "Efek (Belum tersedia)"
                    ).forEach { (icon, label) ->
                        IconButton(
                            onClick = { Toast.makeText(context, label, Toast.LENGTH_SHORT).show() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(icon, contentDescription = label, tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = Color.DarkGray)
        Spacer(Modifier.height(4.dp))

        // Bottom Action Bar (Record, Play, Import, Export)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { Toast.makeText(context, "Impor Audio/Video — Belum tersedia", Toast.LENGTH_SHORT).show() },
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("Impor", fontSize = 11.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = {
                        if (onCheckPermission()) {
                            teleprompterMode = true
                            countdown = 3
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFC63850)),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Rekam Suara", modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { togglePlayback() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (playing) Color(0xFF388E3C) else Panel),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Playback", modifier = Modifier.size(22.dp))
                }
            }

            TextButton(
                onClick = { Toast.makeText(context, "Export MP3/SRT — Belum tersedia di tahap ini", Toast.LENGTH_SHORT).show() },
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("Export", fontSize = 11.sp)
            }
        }
    }
}
