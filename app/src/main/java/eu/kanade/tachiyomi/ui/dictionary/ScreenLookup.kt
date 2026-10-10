package eu.kanade.tachiyomi.ui.dictionary

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.activity.ComponentActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class ScreenLookupPermissionActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))); finish(); return@registerForActivityResult }
            startForegroundService(Intent(this, ScreenLookupService::class.java).apply { action = ScreenLookupService.START; putExtra(ScreenLookupService.CODE, result.resultCode); putExtra(ScreenLookupService.DATA, result.data) })
        }
        finish()
    }
    override fun onCreate(state: Bundle?) { super.onCreate(state); if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) else request() }
    override fun onResume() { super.onResume(); if (Settings.canDrawOverlays(this) && !requested) request() }
    private var requested = false
    private fun request() { requested = true; launcher.launch(getSystemService<MediaProjectionManager>()!!.createScreenCaptureIntent()) }
}

class ScreenLookupService : Service() {
    private val handler = Handler(Looper.getMainLooper()); private var projection: MediaProjection? = null; private var reader: ImageReader? = null; private var display: android.hardware.display.VirtualDisplay? = null; private var button: View? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int { when (intent?.action) { START -> start(intent); CAPTURE -> capture(); STOP -> stopSelf() }; return START_NOT_STICKY }
    private fun start(intent: Intent) { startForeground(7721, NotificationCompat.Builder(this, Notifications.CHANNEL_COMMON).setSmallIcon(R.drawable.ic_komikku).setContentTitle("Screen lookup").setContentText("Tap the floating button to OCR the screen").setOngoing(true).addAction(R.drawable.ic_close_24dp, "Stop", PendingIntent.getService(this, 1, Intent(this, ScreenLookupService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)).build()); val mgr = getSystemService<MediaProjectionManager>() ?: return; val projectionIntent = intent.getParcelableExtra<Intent>(DATA) ?: return; projection = mgr.getMediaProjection(intent.getIntExtra(CODE, 0), projectionIntent); showButton() }
    private fun showButton() { if (button != null || !Settings.canDrawOverlays(this)) return; button = TextView(this).apply { text = "OCR"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xff6750a4.toInt()) }; setOnClickListener { capture() } }; val p = WindowManager.LayoutParams(64, 64, if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT); p.gravity = Gravity.END or Gravity.CENTER_VERTICAL; getSystemService<WindowManager>()!!.addView(button, p) }
    private fun capture() { val wm = getSystemService<WindowManager>()!!; val metrics = resources.displayMetrics; val r = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2); reader = r; display = projection?.createVirtualDisplay("KomikkuScreenLookup", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler); button?.visibility = View.GONE; handler.postDelayed({ val image = r.acquireLatestImage(); if (image == null) { button?.visibility = View.VISIBLE; return@postDelayed }; val plane = image.planes[0]; val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888); bmp.copyPixelsFromBuffer(plane.buffer); image.close(); button?.visibility = View.VISIBLE; ocr(bmp) }, 400) }
    private fun ocr(bitmap: Bitmap) { CoroutineScope(Dispatchers.IO).launch { val input = InputImage.fromBitmap(bitmap, 0); val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()); val text = try { val a = latin.process(input).awaitText(); val b = japanese.process(input).awaitText(); (a + "\n" + b).trim().replace(Regex("\\n+"), "\n") } finally { latin.close(); japanese.close(); bitmap.recycle() }; withContext(Dispatchers.Main) { AlertDialog.Builder(this@ScreenLookupService).setTitle("Screen OCR").setMessage(text.ifBlank { "No text found" }).setPositiveButton("Close", null).create().also { dialog -> dialog.window?.setType(if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE); dialog.show() } } } }
    override fun onDestroy() { button?.let { runCatching { getSystemService<WindowManager>()!!.removeView(it) } }; display?.release(); reader?.close(); projection?.stop(); super.onDestroy() }
    companion object { const val START = "start"; const val CAPTURE = "capture"; const val STOP = "stop"; const val CODE = "code"; const val DATA = "data" }
}

private suspend fun com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text>.awaitText(): String = suspendCancellableCoroutine { cont -> addOnSuccessListener { cont.resume(it.text) }.addOnFailureListener { cont.resume("") } }
