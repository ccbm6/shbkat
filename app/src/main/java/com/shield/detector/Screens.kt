package com.shield.detector

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ShieldApp(vm: MainVM) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("🛡️" to "شامل", "📶" to "واي فاي", "🌐" to "الشبكة", "🔵" to "بلوتوث", "🔍" to "العدسة")
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, (e, l) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Text(e, fontSize = 20.sp) }, label = { Text(l, fontSize = 11.sp, maxLines = 1) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> SweepTab(vm)
                1 -> ListTab(vm, Kind.WIFI)
                2 -> ListTab(vm, Kind.LAN)
                3 -> ListTab(vm, Kind.BLE)
                else -> LensTab()
            }
        }
    }
}

@Composable
fun SweepTab(vm: MainVM) {
    val all by vm.findings.collectAsState()
    val busy by vm.busy.collectAsState()
    val ctx = LocalContext.current
    val flat = all.values.flatten()
    val high = flat.count { it.risk == Risk.HIGH }
    val med = flat.count { it.risk == Risk.MEDIUM }
    val suspicious = flat.filter { it.risk != Risk.LOW }.sortedByDescending { it.score }.take(20)

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Button(onClick = { vm.scanAll() }, enabled = busy.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy.isEmpty()) "ابدأ الفحص الشامل" else "جارٍ الفحص…")
            }
        }
        if (busy.isNotEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (all.isNotEmpty()) {
            item {
                val (msg, bg) = when {
                    high > 0 -> "⚠️ رُصدت $high مؤشرات عالية الخطورة و$med متوسطة. افحص الأجهزة المعلّمة فعلياً في المكان." to Color(0xFFFFDAD6)
                    med > 0 -> "🟠 رُصدت $med مؤشرات متوسطة. راجعها وعلّم ما تعرفه بأنه موثوق." to Color(0xFFFFE9B8)
                    else -> "✅ لا مؤشرات مقلقة ضمن ما يلتقطه هاتفك. هذا لا يعني ضماناً كاملاً." to Color(0xFFE3F1E3)
                }
                Card(colors = CardDefaults.cardColors(containerColor = bg)) {
                    Text(msg, Modifier.padding(14.dp), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            items(suspicious, key = { it.kind.name + it.key }) { f -> FindingCard(f) { vm.toggleTrust(f.key) } }
            item {
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, vm.report())
                    }, null))
                }, modifier = Modifier.fillMaxWidth()) { Text("مشاركة التقرير") }
            }
        }
        item {
            Card { Column(Modifier.padding(14.dp)) {
                Text("خطوات فحص الغرفة", fontWeight = FontWeight.Bold)
                Text("1) شغّل الموقع والبلوتوث واتصل بشبكة المكان.\n" +
                    "2) نفّذ الفحص الشامل وراجع النتائج المعلّمة.\n" +
                    "3) أطفئ الضوء وجرّب تبويب «العدسة» مع الفلاش وحرّك الهاتف ببطء.\n" +
                    "4) افحص فعلياً: ساعات الحائط، كاشفات الدخان، الشواحن، مخارج الكهرباء، المرايا.\n" +
                    "5) كرّر الفحص بعد دقائق؛ الأجهزة «الجديدة» تُعلَّم تلقائياً.", fontSize = 13.sp)
            } }
        }
    }
}

@Composable
fun ListTab(vm: MainVM, kind: Kind) {
    val all by vm.findings.collectAsState()
    val busy by vm.busy.collectAsState()
    val notes by vm.notes.collectAsState()
    val progress by vm.progress.collectAsState()
    val list = all[kind].orEmpty()

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Button(onClick = { vm.scan(kind) }, enabled = kind !in busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (kind in busy) "جارٍ الفحص…" else "فحص ${kind.label}")
            }
        }
        if (kind in busy) item {
            if (kind == Kind.LAN) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        notes[kind]?.takeIf { it.isNotBlank() }?.let { item { Text(it, fontSize = 13.sp) } }
        if (list.isNotEmpty()) item { Text("${list.size} نتيجة، مرتبة من الأخطر", fontSize = 12.sp) }
        items(list, key = { it.key }) { f -> FindingCard(f) { vm.toggleTrust(f.key) } }
    }
}

@Composable
fun FindingCard(f: Finding, onTrust: () -> Unit) {
    val (bg, fg) = when (f.risk) {
        Risk.HIGH -> Color(0xFFFFDAD6) to Color(0xFF7A0010)
        Risk.MEDIUM -> Color(0xFFFFE9B8) to Color(0xFF5C4100)
        Risk.LOW -> Color(0xFFE3F1E3) to Color(0xFF1B5E20)
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.title, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.weight(1f))
                if (f.isNew) Text("جديد ", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("${f.risk.label} ${f.score}", color = fg, fontSize = 12.sp)
            }
            Text(f.subtitle, fontSize = 12.sp, color = fg.copy(alpha = 0.85f))
            f.reasons.forEach { Text("• $it", fontSize = 13.sp, color = fg) }
            TextButton(onClick = onTrust) { Text(if (f.trusted) "إلغاء الثقة" else "هذا جهازي / موثوق", color = fg) }
        }
    }
}
