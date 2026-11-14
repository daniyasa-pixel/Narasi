package com.appik.narasi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF11141C)
private val Panel = Color(0xFF1D2230)
private val Purple = Color(0xFFB8A0FF)
private val Muted = Color(0xFFAAB3C5)
private data class Track(val id: Int, val kind: String, val name: String)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Bg, surface = Panel)) { StudioScreen() } }
    }
}
@Composable
private fun StudioScreen() {
    val tracks = remember { mutableStateListOf(Track(1,"VO","VO 1"),Track(2,"Music","Music 1"),Track(3,"Subtitle","SRT 1")) }
    var nextId by remember { mutableIntStateOf(4) }
    var script by remember { mutableStateOf("Selamat datang di Yasa CCTV.\nKami menyediakan solusi keamanan untuk rumah dan tempat usaha Anda.") }
    var recording by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var magnet by remember { mutableStateOf(true) }
    var enhance by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf("Studio") }
    var speed by remember { mutableFloatStateOf(3f) }
    var noise by remember { mutableFloatStateOf(0.65f) }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text("🎙 Voice Over Studio",fontWeight=FontWeight.Bold,fontSize=18.sp)
            Icon(Icons.Default.Settings,contentDescription="Pengaturan",tint=Muted)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=!enhance,onClick={enhance=false},label={Text("Editor")})
            FilterChip(selected=enhance,onClick={enhance=true},label={Text("Enhance")})
        }
        if(enhance) {
            Text("Preset",color=Muted)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("Natural","Studio","Deep Bass","Crispy").forEach { p ->
                    FilterChip(selected=preset==p,onClick={preset=p},label={Text(p)})
                }
            }
            Text("AI Noise Reduction (RNNoise — belum terintegrasi)")
            Slider(value=noise,onValueChange={noise=it})
            Text("EQ, Compressor, Auto Level — tahap berikutnya",color=Muted,fontSize=12.sp)
        } else {
            Column(Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(if(recording)"TELEPROMPTER • MODE REKAM (SIMULASI)" else "NASKAH / TELEPROMPTER",color=Purple,fontSize=12.sp)
                    Spacer(Modifier.height(8.dp))
                    if(recording) {
                        Box(Modifier.fillMaxWidth().height(128.dp).verticalScroll(rememberScrollState())) {
                            Text(script,fontSize=22.sp,lineHeight=34.sp)
                        }
                        Text("Kecepatan teks: ${speed.toInt()}×",color=Muted)
                        Slider(value=speed,onValueChange={speed=it},valueRange=1f..5f)
                    } else {
                        BasicTextField(value=script,onValueChange={script=it},textStyle=androidx.compose.ui.text.TextStyle(color=Color.White,fontSize=15.sp),modifier=Modifier.fillMaxWidth().heightIn(min=65.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().background(Color(0xFF080B12),RoundedCornerShape(10.dp)).padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("Selamat datang di Yasa CCTV.",fontWeight=FontWeight.SemiBold)
                    Text("Subtitle Preview • simulasi",color=Muted,fontSize=11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Timeline",fontWeight=FontWeight.Bold)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={magnet=!magnet}) { Icon(Icons.Default.AutoFixHigh,contentDescription="Magnet",tint=if(magnet)Purple else Muted) }
                        IconButton(onClick={}) { Icon(Icons.Default.Undo,contentDescription="Undo") }
                        IconButton(onClick={}) { Icon(Icons.Default.Redo,contentDescription="Redo") }
                        IconButton(onClick={}) { Icon(Icons.Default.ZoomIn,contentDescription="Zoom") }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).background(Panel,RoundedCornerShape(12.dp)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    item { Text("00:00          00:05          00:10          00:15",color=Muted,fontSize=10.sp) }
                    items(tracks,key={it.id}) { t ->
                        Column {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                Text(t.name,color=Muted,fontSize=12.sp)
                                IconButton(onClick={tracks.remove(t)},modifier=Modifier.size(24.dp)) { Icon(Icons.Default.Close,contentDescription="Hapus track",modifier=Modifier.size(14.dp)) }
                            }
                            Box(Modifier.fillMaxWidth(if(t.kind=="VO")0.7f else 0.95f).height(37.dp).background(when(t.kind){"VO"->Color(0xFF6650A4);"Music"->Color(0xFF176B64);"SFX"->Color(0xFF8A5B22);else->Color(0xFF865D26)},RoundedCornerShape(5.dp)).padding(8.dp)) {
                                Text(if(t.kind=="Subtitle")"Subtitle..." else "▂▅▃▇▂▄▆▃▅▂▇▃▆▂▅▃▇▂",fontSize=12.sp,maxLines=1,overflow=TextOverflow.Clip)
                            }
                        }
                    }
                    item {
                        Box {
                            IconButton(onClick={addMenu=true},modifier=Modifier.align(Alignment.CenterEnd)) {
                                Icon(Icons.Default.AddCircle,contentDescription="Tambah track",tint=Purple,modifier=Modifier.size(32.dp))
                            }
                            DropdownMenu(expanded=addMenu,onDismissRequest={addMenu=false}) {
                                listOf("VO" to "Voice Over","Music" to "Background Music","SFX" to "Sound Effect","Subtitle" to "Subtitle").forEach { (kind,label) ->
                                    DropdownMenuItem(text={Text(label)},onClick={
                                        val count=tracks.count{it.kind==kind}+1
                                        tracks.add(Track(nextId++,kind,when(kind){"VO"->"VO $count";"Music"->"Music $count";"SFX"->"SFX $count";else->"SRT $count"}))
                                        addMenu=false
                                    })
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf(Icons.Default.ContentCut to "Split",Icons.Default.Delete to "Hapus",Icons.Default.ContentCopy to "Duplikat",Icons.Default.VolumeUp to "Volume",Icons.Default.GraphicEq to "Fade",Icons.Default.Tune to "Efek").forEach { (icon,label) ->
                        IconButton(onClick={},modifier=Modifier.size(44.dp)) { Icon(icon,contentDescription=label,tint=Muted) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color=Color.DarkGray)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={}) { Icon(Icons.Default.Upload,contentDescription=null); Text("Impor") }
            Row(verticalAlignment=Alignment.CenterVertically) {
                FilledIconButton(onClick={recording=!recording},colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFFC63850)),modifier=Modifier.size(56.dp)) {
                    Icon(if(recording)Icons.Default.Stop else Icons.Default.Mic,contentDescription=if(recording)"Selesai simulasi rekam" else "Simulasi rekam")
                }
                Spacer(Modifier.width(12.dp))
                FilledIconButton(onClick={playing=!playing},modifier=Modifier.size(56.dp)) {
                    Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,contentDescription="Simulasi playback")
                }
            }
            TextButton(onClick={}) { Icon(Icons.Default.Download,contentDescription=null);Text("Export") }
        }
    }
}
