package com.example.vrupsidedown

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Vue VR (double image) à l'envers.
 * Sources : caméra arrière en direct, ou photo / vidéo importée depuis le téléphone.
 *
 * Toucher l'écran      : change l'orientation (180° / miroir vertical / normal)
 * Appui long           : réglages + choix de la source
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: StereoRenderer
    private lateinit var prefs: SharedPreferences
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var player: MediaPlayer? = null
    private var videoSurface: Surface? = null

    @Volatile
    private var cameraActive = false

    private val flipNames = arrayOf("À l'envers (180°)", "Miroir vertical", "Normal")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else toast("Permission caméra refusée. Appui long pour importer une photo/vidéo.")
        }

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadMedia(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        val crashFile = File(filesDir, "crash.txt")
        if (crashFile.exists()) {
            val trace = crashFile.readText()
            crashFile.delete()
            showCrashScreen(trace)
            return
        }
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        prefs = getSharedPreferences("vr", MODE_PRIVATE)
        cameraExecutor = Executors.newSingleThreadExecutor()

        renderer = StereoRenderer { surface ->
            runOnUiThread {
                videoSurface = surface
                try {
                    player?.setSurface(surface)
                } catch (e: Exception) {
                }
            }
        }
        renderer.distortion = prefs.getFloat("k", 0.15f)
        renderer.eyeShift = prefs.getFloat("shift", 0f)
        renderer.zoom = prefs.getFloat("zoom", 1f)
        renderer.flipMode = prefs.getInt("flip", 0)

        glView = findViewById(R.id.glView)
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent?) = true
            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                cycleFlip()
                return true
            }

            override fun onLongPress(e: MotionEvent?) {
                showSettings()
            }
        })
        glView.setOnTouchListener { _, e -> detector.onTouchEvent(e) }

        useCamera()
        toast("Toucher : orientation • Appui long : réglages / import")
    }

    // ---------------------------------------------------------------- sources

    private fun useCamera() {
        stopPlayer()
        renderer.source = StereoRenderer.Source.BITMAP
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val a = ImageAnalysis.Builder()
                .setTargetResolution(Size(800, 600))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            a.setAnalyzer(cameraExecutor) { image ->
                try {
                    if (!cameraActive) return@setAnalyzer
                    val src = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    val bmp = if (rot == 0) src else {
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                    }
                    renderer.submitBitmap(bmp)
                } finally {
                    image.close()
                }
            }
            analysis = a
            cameraActive = true
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, a)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraActive = false
        cameraProvider?.unbindAll()
    }

    private fun loadMedia(uri: Uri) {
        stopCamera()
        stopPlayer()
        val type = contentResolver.getType(uri) ?: ""
        if (type.startsWith("video")) playVideo(uri) else showImage(uri)
    }

    private fun showImage(uri: Uri) {
        cameraExecutor.execute {
            val bmp = decodeBitmap(uri)
            if (bmp == null) {
                runOnUiThread { toast("Impossible de lire cette image") }
            } else {
                renderer.source = StereoRenderer.Source.BITMAP
                renderer.submitBitmap(bmp)
            }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val orientation = contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
            val deg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) bmp
            else Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun playVideo(uri: Uri) {
        val surface = videoSurface
        if (surface == null) {
            toast("Surface vidéo pas prête, réessaie")
            return
        }
        renderer.resetVideo()
        renderer.source = StereoRenderer.Source.VIDEO
        try {
            player = MediaPlayer().apply {
                setSurface(surface)
                setDataSource(this@MainActivity, uri)
                isLooping = true
                setOnVideoSizeChangedListener { _, w, h ->
                    if (h > 0) renderer.videoAspect = w.toFloat() / h.toFloat()
                }
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ ->
                    toast("Impossible de lire cette vidéo")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            toast("Impossible de lire cette vidéo")
        }
    }

    private fun stopPlayer() {
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
    }

    // --------------------------------------------------------------- réglages

    private fun cycleFlip() {
        renderer.flipMode = (renderer.flipMode + 1) % flipNames.size
        prefs.edit().putInt("flip", renderer.flipMode).apply()
        toast(flipNames[renderer.flipMode])
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun slider(label: String, min: Float, maxV: Float, value: Float, onChange: (Float) -> Unit) {
            col.addView(TextView(this).apply { text = label })
            col.addView(SeekBar(this).apply {
                max = 100
                progress = ((value - min) / (maxV - min) * 100f).roundToInt().coerceIn(0, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) onChange(min + (maxV - min) * p / 100f)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }

        slider("Correction de distorsion (lentilles)", 0f, 0.6f, renderer.distortion) {
            renderer.distortion = it
            prefs.edit().putFloat("k", it).apply()
        }
        slider("Rapprochement des deux images", -0.3f, 0.3f, renderer.eyeShift) {
            renderer.eyeShift = it
            prefs.edit().putFloat("shift", it).apply()
        }
        slider("Zoom", 0.5f, 2.5f, renderer.zoom) {
            renderer.zoom = it
            prefs.edit().putFloat("zoom", it).apply()
        }

        lateinit var dialog: AlertDialog

        col.addView(Button(this).apply {
            text = "Orientation : ${flipNames[renderer.flipMode]}"
            setOnClickListener {
                cycleFlip()
                text = "Orientation : ${flipNames[renderer.flipMode]}"
            }
        })
        col.addView(Button(this).apply {
            text = "📷  Caméra en direct"
            setOnClickListener {
                dialog.dismiss()
                useCamera()
            }
        })
        col.addView(Button(this).apply {
            text = "📂  Importer une photo / vidéo du téléphone"
            setOnClickListener {
                dialog.dismiss()
                picker.launch(arrayOf("image/*", "video/*"))
            }
        })

        dialog = AlertDialog.Builder(this)
            .setTitle("Réglages")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("OK", null)
            .create()
        dialog.setOnDismissListener { hideSystemBars() }
        dialog.show()
    }

    // ---------------------------------------------------------------- système

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        analysis?.targetRotation = window.decorView.display?.rotation ?: Surface.ROTATION_90
    }

    override fun onResume() {
        super.onResume()
        if (!::glView.isInitialized) return
        glView.onResume()
        try {
            player?.start()
        } catch (e: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::glView.isInitialized) return
        try {
            player?.pause()
        } catch (e: Exception) {
        }
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        stopPlayer()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }

    // ------------------------------------------------------------- diagnostic

    private fun installCrashLogger() {
        if (handlerInstalled) return
        handlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val file = File(applicationContext.filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                file.writeText(Log.getStackTraceString(throwable))
            } catch (e: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun showCrashScreen(trace: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val shown = trace.take(3500)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }
        col.addView(TextView(this).apply {
            text = "L'app a planté au dernier lancement. Appuie sur Copier et envoie le texte."
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        col.addView(Button(this).apply {
            text = "Copier l'erreur"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("erreur", shown))
                toast("Copié")
            }
        })
        col.addView(Button(this).apply {
            text = "Réessayer"
            setOnClickListener { recreate() }
        })
        col.addView(TextView(this).apply {
            text = shown
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setTextIsSelectable(true)
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    companion object {
        @Volatile
        private var handlerInstalled = false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
