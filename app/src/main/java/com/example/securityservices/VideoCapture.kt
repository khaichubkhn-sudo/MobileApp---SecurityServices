package com.example.securityservices

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.io.File

private object Dep {
    @Suppress("DEPRECATION")
    fun newRecorder(): MediaRecorder = MediaRecorder()
    @Suppress("DEPRECATION")
    fun newSession(
        c: CameraDevice,
        s: Surface,
        h: Handler,
        ok: (CameraCaptureSession) -> Unit,
        fail: () -> Unit
    ) {
        c.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(x: CameraCaptureSession) = ok(x)
            override fun onConfigureFailed(x: CameraCaptureSession) = fail()
        }, h)
    }
}

// Back-camera video recorder, no preview surface.
// Prefers MPEG-2 TS (.ts): any byte prefix split at 188-byte packet borders
// stays playable; rejoins via plain concatenation (copy /b, cat).
// Falls back to MP4 when the device refuses TS.
class VideoCapture(
    private val context: Context,
    private val cameraManager: CameraManager
) {
    private var thread: HandlerThread? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var finished = false
    private var useTs = true

    @Volatile
    var isRecording = false
        private set

    val startedWithTs: Boolean get() = useTs

    fun start(
        outFile: File,
        includeAudio: Boolean,
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRecording) {
            onError("already recording")
            return
        }
        val cameraId = pickId() ?: run {
            onError("no back camera found")
            return
        }
        val t = HandlerThread("SecVideo").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        outputFile = outFile
        finished = false
        val rec: MediaRecorder = try {
            if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
            else Dep.newRecorder()
        } catch (e: Exception) {
            cleanup()
            onError(e.message ?: "recorder error")
            return
        }
        recorder = rec
        useTs = true
        if (!prep(rec, cameraId, outFile, includeAudio)) {
            cleanup()
            onError("prepare failed")
            return
        }
        h.postDelayed({
            if (!isRecording && !finished) {
                cleanup()
                try { outFile.delete() } catch (_: Exception) { }
                onError("camera timed out")
            }
        }, 10_000L)
        open(cameraId, h, rec, outFile, onStarted, onError)
    }

    private fun prep(
        rec: MediaRecorder,
        cameraId: String,
        outFile: File,
        includeAudio: Boolean
    ): Boolean {
        return try {
            if (includeAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            var tsOk = true
            try {
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
            } catch (_: Exception) {
                tsOk = false
            }
            if (!tsOk) {
                rec.reset()
                if (includeAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                useTs = false
            }
            val size = pickSize(cameraId)
            rec.setVideoSize(size.width, size.height)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoEncodingBitRate(5_000_000)
            rec.setVideoFrameRate(30)
            if (includeAudio) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            }
            rec.setOutputFile(outFile.absolutePath)
            rec.prepare()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun open(
        cameraId: String,
        h: Handler,
        rec: MediaRecorder,
        outFile: File,
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    sess(camera, h, rec, outFile, onStarted, onError)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) { }
                    if (!isRecording && !finished) {
                        finished = true
                        cleanup()
                        onError("camera disconnected")
                    }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Exception) { }
                    if (!isRecording && !finished) {
                        finished = true
                        cleanup()
                        onError("camera error $error")
                    }
                }
            }, h)
        } catch (e: SecurityException) {
            finished = true
            cleanup()
            onError("camera permission denied")
        } catch (e: Exception) {
            finished = true
            cleanup()
            onError(e.message ?: "camera error")
        }
    }

    private fun sess(
        camera: CameraDevice,
        h: Handler,
        rec: MediaRecorder,
        outFile: File,
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val surface = rec.surface
            val req = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_RECORD
            ).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            Dep.newSession(camera, surface, h,
                ok = { s ->
                    session = s
                    try {
                        s.setRepeatingRequest(req, null, h)
                        rec.start()
                        isRecording = true
                        onStarted()
                    } catch (e: Exception) {
                        finished = true
                        cleanup()
                        try { outFile.delete() } catch (_: Exception) { }
                        onError(e.message ?: "start failed")
                    }
                },
                fail = {
                    finished = true
                    cleanup()
                    try { outFile.delete() } catch (_: Exception) { }
                    onError("camera session failed")
                })
        } catch (e: SecurityException) {
            finished = true
            cleanup()
            onError("camera permission denied")
        } catch (e: Exception) {
            finished = true
            cleanup()
            try { outFile.delete() } catch (_: Exception) { }
            onError(e.message ?: "camera error")
        }
    }
    fun stop(done: (File?) -> Unit) {
        val rec = recorder
        val file = outputFile
        if (rec == null) {
            done(null)
            return
        }
        if (finished) {
            val ok = file != null && file.exists() && file.length() > 0
            done(if (ok) file else null)
            return
        }
        finished = true
        isRecording = false
        try { session?.stopRepeating() } catch (_: Exception) { }
        var bad = false
        try {
            rec.stop()
        } catch (_: Exception) {
            bad = true
        }
        try { rec.reset() } catch (_: Exception) { }
        try { rec.release() } catch (_: Exception) { }
        recorder = null
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { device?.close() } catch (_: Exception) { }
        device = null
        try { thread?.quitSafely() } catch (_: Exception) { }
        thread = null
        if (bad) {
            try { file?.delete() } catch (_: Exception) { }
            done(null)
            return
        }
        val ok = file != null && file.exists() && file.length() > 0
        done(if (ok) file else null)
    }

    private fun cleanup() {
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { device?.close() } catch (_: Exception) { }
        device = null
        try { recorder?.reset() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { thread?.quitSafely() } catch (_: Exception) { }
        thread = null
        isRecording = false
    }

    private fun pickId(): String? {
        return try {
            val ids = cameraManager.cameraIdList
            var back: String? = null
            for (id in ids) {
                val f = cameraManager.getCameraCharacteristics(id).get(
                    CameraCharacteristics.LENS_FACING
                )
                if (f == CameraCharacteristics.LENS_FACING_BACK) back = id
            }
            back ?: ids.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun pickSize(cameraId: String): Size {
        return try {
            val map = cameraManager.getCameraCharacteristics(cameraId).get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            val sizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
            if (sizes.isEmpty()) return Size(1280, 720)
            var best720: Size? = null
            var bestSmall: Size? = null
            var smallest: Size? = null
            for (s in sizes) {
                if (s.width == 1280 && s.height == 720) best720 = s
                if (s.width <= 1920 && s.height <= 1080) {
                    if (bestSmall == null) bestSmall = s
                    else if (s.width * s.height > bestSmall.width * bestSmall.height) bestSmall = s
                }
                if (smallest == null) smallest = s
                else if (s.width * s.height < smallest.width * smallest.height) smallest = s
            }
            best720 ?: bestSmall ?: smallest!!
        } catch (_: Exception) {
            Size(1280, 720)
        }
    }
}


