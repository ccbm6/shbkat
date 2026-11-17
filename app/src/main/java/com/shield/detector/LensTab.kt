package com.shield.detector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

data class Spot(val x: Float, val y: Float, val size: Int)

object SpotAnalyzer {
    private val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

    /** يبحث عن نقاط لامعة صغيرة (انعكاس عدسة بالفلاش) أو بنفسجية/ساطعة (أشعة تحت حمراء بالكاميرا الأمامية). */
    fun analyze(img: ImageProxy, ir: Boolean): List<Spot> {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val w = img.width
        val h = img.height
        val bs = 8
        val gw = w / bs
        val gh = h / bs
        if (gw == 0 || gh == 0) return emptyList()
        val hot = IntArray(gw * gh)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val i = y * rs + x * ps
                if (i + 2 < buf.limit()) {
                    val r = buf.get(i).toInt() and 0xFF
                    val g = buf.get(i + 1).toInt() and 0xFF
                    val b = buf.get(i + 2).toInt() and 0xFF
                    val bright = if (ir)
                        (b > 150 && b - g > 40 && r - g > 15) || (r > 248 && g > 248 && b > 248)
                    else r > 240 && g > 240 && b > 240
                    if (bright) {
                        val cx = x / bs
                        val cy = y / bs
                        if (cx < gw && cy < gh) hot[cy * gw + cx]++
                    }
                }
                x += 2
            }
            y += 2
        }
        val rot = img.imageInfo.rotationDegrees
        val seen = BooleanArray(gw * gh)
        val out = mutableListOf<Spot>()
        for (s in hot.indices) {
            if (hot[s] < 3 || seen[s]) continue
            val q = ArrayDeque<Int>()
            q.add(s); seen[s] = true
            var n = 0
            var sx = 0L
            var sy = 0L
            while (q.isNotEmpty()) {
                val c = q.removeFirst()
                n++
                val cx = c % gw
                val cy = c / gw
                sx += cx; sy += cy
                for (d in dirs) {
                    val nx = cx + d[0]
                    val ny = cy + d[1]
                    if (nx in 0 until gw && ny in 0 until gh) {
                        val ni = ny * gw + nx
                        if (!seen[ni] && hot[ni] >= 3) { seen[ni] = true; q.add(ni) }
                    }
                }
            }
            if (n in 1..10) { // البقع الكبيرة = مصابيح/نوافذ، نتجاهلها
                val nx = (sx.toFloat() / n + 0.5f) / gw
                val ny = (sy.toFloat() / n + 0.5f) / gh
                val (u, v) = when (rot) {
                    90 -> (1 - ny) to nx
                    180 -> (1 - nx) to (1 - ny)
                    270 -> ny to (1 - nx)
                    else -> nx to ny
                }
                out.add(Spot(if (ir) 1 - u else u, v, n))
            }
            if (out.size >= 12) break
        }
        return out
    }
}

@Composable
fun LensTab() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var front by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(true) }
    var spots by remember { mutableStateOf<List<Spot>>(emptyList()) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!granted) {
            Text("يحتاج هذا الوضع إذن الكاميرا.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("منح إذن الكاميرا") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !front, onClick = { front = false }, label = { Text("انعكاس العدسة (خلفية+فلاش)") })
                FilterChip(selected = front, onClick = { front = true }, label = { Text("أشعة تحت حمراء (أمامية)") })
            }
            if (!front) FilterChip(selected = torch, onClick = { torch = !torch }, label = { Text("الفلاش") })

            Box(Modifier.fillMaxWidth().height(380.dp)) {
                CameraBox(front, torch) { spots = it }
                Canvas(Modifier.fillMaxSize()) {
                    spots.forEach {
                        drawCircle(Color.Red, radius = 28f + it.size * 3f, center = Offset(it.x * size.width, it.y * size.height), style = Stroke(5f))
                    }
                }
            }
            Text(
                if (spots.isEmpty()) "لا نقاط مشتبهة حالياً" else "نقاط مشتبهة: ${spots.size} (ثبّت الهاتف وتأكد بالنظر المباشر)",
                fontWeight = FontWeight.Bold, color = if (spots.isEmpty()) Color.Unspecified else Color(0xFFB00020)
            )
            Text(
                if (!front) "أطفئ ضوء الغرفة، وحرّك الهاتف ببطء. عدسة الكاميرا تعكس الفلاش بنقطة ساطعة صغيرة ثابتة المكان. المرايا والأسطح اللامعة قد تعطي نتائج خاطئة."
                else "بعض الكاميرات الليلية تُظهر نقاطاً بنفسجية بالكاميرا الأمامية في الظلام. الكاميرا الأمامية تلتقط الأشعة تحت الحمراء غالباً أفضل من الخلفية.",
                fontSize = 13.sp
            )
        }
        MagnetCard()
    }
}

@Composable
private fun CameraBox(front: Boolean, torch: Boolean, onSpots: (List<Spot>) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(ctx) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val cb by rememberUpdatedState(onSpots)

    DisposableEffect(front, torch) {
        val future = ProcessCameraProvider.getInstance(ctx)
        val exec = Executors.newSingleThreadExecutor()
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { img ->
                    val r = try { SpotAnalyzer.analyze(img, front) } catch (_: Exception) { emptyList() }
                    img.close()
                    handler.post { cb(r) }
                }
                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    owner,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis
                )
                if (!front && cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(torch)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { future.get().unbindAll() }
            exec.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

@Composable
fun MagnetCard() {
    val ctx = LocalContext.current
    var mag by remember { mutableFloatStateOf(0f) }
    var base by remember { mutableStateOf<Float?>(null) }
    var has by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values
                val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                mag = if (mag == 0f) m else mag * 0.8f + m * 0.2f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI) else has = false
        onDispose { sm.unregisterListener(l) }
    }
    val delta = base?.let { abs(mag - it) } ?: 0f
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("حساس المغناطيس (أداة مساعدة ضعيفة)", fontWeight = FontWeight.Bold)
            if (!has) Text("هاتفك لا يحتوي على حساس مغناطيسي.")
            else {
                Text("المجال الحالي: ${"%.1f".format(mag)} µT" + (base?.let { " • الفرق: ${"%.1f".format(delta)}" } ?: ""))
                LinearProgressIndicator(progress = { (delta / 60f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                if (delta > 25f) Text("تغيّر كبير بالمجال: قد يدل على معدن/مغناطيس/إلكترونيات قريبة.", color = Color(0xFFB00020), fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { base = mag }) { Text("معايرة هنا") }
                }
                Text("ابتعد عن الأجهزة، اضغط معايرة، ثم قرّب الهاتف من الأجسام المشبوهة. الكاميرات الصغيرة نادراً ما تُحدث تغيّراً واضحاً.", fontSize = 12.sp)
            }
        }
    }
}
