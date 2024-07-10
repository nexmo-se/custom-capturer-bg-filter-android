package com.tokbox.sample.basicvideocapturercamera2

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SparseArray
import android.util.SparseIntArray
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.opentok.android.BaseVideoCapturer
import com.opentok.android.BaseVideoCapturer.CaptureSwitch
import com.opentok.android.Publisher.CameraCaptureFrameRate
import com.opentok.android.Publisher.CameraCaptureResolution
import java.util.Arrays
import java.util.Collections
import kotlin.math.abs

@TargetApi(21)
@RequiresApi(21)
class MirrorVideoCapturer(
    ctx: Context,
    resolution: CameraCaptureResolution,
    fps: CameraCaptureFrameRate
) : BaseVideoCapturer(), CaptureSwitch {
    private enum class CameraState {
        NONE,
        CLOSED,
        CLOSING,
        SETUP,
        OPEN,
        CAPTURE,
        CREATESESSION,
        ERROR
    }
    private val cameraManager: CameraManager
    private var camera: CameraDevice? = null
    private var cameraThread: HandlerThread? = null
    private var cameraThreadHandler: Handler? = null
    private var cameraFrame: ImageReader? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraInfoCache: CameraInfoCache? = null
    private var cameraState: CameraState = CameraState.NONE
    private val display: Display
    private var displayOrientationCache: DisplayOrientationCache? = null
    private var cameraIndex = 0
    private val frameDimensions: Size
    private val desiredFps: Int
    private var camFps: Range<Int>? = null
    private val runtimeExceptionList: MutableList<RuntimeException>
    private var executeAfterClosed: Runnable? = null
    private var executeAfterCameraOpened: Runnable? = null

    /* Observers/Notification callback objects */
    private val cameraObserver: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "CameraDevice.StateCallback onOpened() enter")
            cameraState = CameraState.OPEN
            this@MirrorVideoCapturer.camera = camera
            if (executeAfterCameraOpened != null) {
                executeAfterCameraOpened!!.run()
            }
            executeAfterCameraOpened = null
            Log.d(TAG, "CameraDevice.StateCallback onOpened() exit")
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "CameraDevice.StateCallback onDisconnected() enter")
            try {
                executeAfterClosed = null
                this@MirrorVideoCapturer.camera!!.close()
            } catch (e: NullPointerException) {
                // does nothing
            }
            Log.d(TAG, "CameraDevice.StateCallback onDisconnected() exit")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.d(TAG, "CameraDevice.StateCallback onError() enter")
            try {
                this@MirrorVideoCapturer.camera!!.close()
                // wait for condition variable
            } catch (e: NullPointerException) {
                // does nothing
            }
            postAsyncException(
                Camera2Exception(
                    "Camera Open Error: $error"
                )
            )
            Log.d(TAG, "CameraDevice.StateCallback onError() exit")
        }

        override fun onClosed(camera: CameraDevice) {
            Log.d(TAG, "CameraDevice.StateCallback onClosed() enter.")
            super.onClosed(camera)
            cameraState = CameraState.CLOSED
            this@MirrorVideoCapturer.camera = null
            if (executeAfterClosed != null) {
                executeAfterClosed!!.run()
            }
            executeAfterClosed = null
            Log.d(TAG, "CameraDevice.StateCallback onClosed() exit.")
        }
    }
    private val frameObserver: OnImageAvailableListener = object : OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            try {
                val frame = reader.acquireNextImage()
                if (frame == null || frame.planes.size > 0 && frame.planes[0].buffer == null || frame.planes.size > 1 && frame.planes[1].buffer == null || frame.planes.size > 2 && frame.planes[2].buffer == null) {
                    Log.d(TAG, "onImageAvailable frame provided has no image data")
                    return
                }
                if (CameraState.CAPTURE == cameraState) {
                    provideBufferFramePlanar(
                        frame.planes[0].buffer,
                        frame.planes[1].buffer,
                        frame.planes[2].buffer,
                        frame.planes[0].pixelStride,
                        frame.planes[0].rowStride,
                        frame.planes[1].pixelStride,
                        frame.planes[1].rowStride,
                        frame.planes[2].pixelStride,
                        frame.planes[2].rowStride,
                        frame.width,
                        frame.height,
                        calculateCamRotation(),
                        isFrontCamera
                    )
                }
                frame.close()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "ImageReader.acquireNextImage() throws error !")
                throw Camera2Exception(e.message)
            }
        }
    }
    private val captureSessionObserver: CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "CameraCaptureSession.StateCallback onConfigured() enter.")
                try {
                    cameraState = CameraState.CAPTURE
                    captureSession = session
                    val captureRequest = captureRequestBuilder!!.build()
                    captureSession!!.setRepeatingRequest(captureRequest, captureNotification, null)
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                }
                Log.d(TAG, "CameraCaptureSession.StateCallback onConfigured() exit.")
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.d(TAG, "CameraCaptureSession.StateCallback onFailed() enter.")
                cameraState = CameraState.ERROR
                postAsyncException(Camera2Exception("Camera session configuration failed"))
                Log.d(TAG, "CameraCaptureSession.StateCallback onFailed() exit.")
            }

            override fun onClosed(session: CameraCaptureSession) {
                Log.d(TAG, "CameraCaptureSession.StateCallback onClosed() enter.")
                if (camera != null) {
                    camera!!.close()
                }
                Log.d(TAG, "CameraCaptureSession.StateCallback onClosed() exit.")
            }
        }
    private val captureNotification: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession, request: CaptureRequest,
            timestamp: Long, frameNumber: Long
        ) {
            super.onCaptureStarted(session, request, timestamp, frameNumber)
        }
    }

    /* caching of camera characteristics & display orientation for performance */
    private class CameraInfoCache(private val info: CameraCharacteristics) {
        var isFrontFacing = false
        private var sensorOrientation = 0

        init {
            /* its actually faster to cache these results then to always look
               them up, and since they are queried every frame...
             */isFrontFacing = (info.get(CameraCharacteristics.LENS_FACING)
                    == CameraCharacteristics.LENS_FACING_FRONT)
            sensorOrientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        }

        operator fun <T> get(key: CameraCharacteristics.Key<T>?): T? {
            return info.get(key)
        }

        fun sensorOrientation(): Int {
            return sensorOrientation
        }
    }

    private class DisplayOrientationCache(
        private val display: Display,
        private val handler: Handler?
    ) :
        Runnable {
        var orientation: Int
            private set

        init {
            orientation = rotationTable[display.rotation]
            handler!!.postDelayed(this, POLL_DELAY_MS.toLong())
        }

        override fun run() {
            orientation = rotationTable[display.rotation]
            handler!!.postDelayed(this, POLL_DELAY_MS.toLong())
        }

        companion object {
            private const val POLL_DELAY_MS = 750 /* 750 ms */
        }
    }

    /* custom exceptions */
    class Camera2Exception(message: String?) : RuntimeException(message)

    /* Constructors etc... */
    init {
        cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        display = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        cameraState = CameraState.CLOSED
        frameDimensions = resolutionTable[resolution.ordinal]!!
        desiredFps = frameRateTable[fps.ordinal]
        runtimeExceptionList = ArrayList()
        try {
            var camId = selectCamera(PREFERRED_FACING_CAMERA)
            /* if default camera facing direction is not found, use first camera */if (null == camId && 0 < cameraManager.cameraIdList.size) {
                camId = cameraManager.cameraIdList[0]
            }
            cameraIndex = findCameraIndex(camId)
            initCameraFrame()
        } catch (e: CameraAccessException) {
            throw Camera2Exception(e.message)
        }
    }

    private fun doInit() {
        Log.d(TAG, "doInit() enter")
        cameraInfoCache = null
        // start camera looper thread
        startCamThread()
        // start display orientation polling
        startDisplayOrientationCache()
        // open selected camera
        initCamera()
        Log.d(TAG, "doInit() exit")
    }

    /**
     * Initializes the video capturer.
     */
    @Synchronized
    override fun init() {
        Log.d(TAG, "init() enter")
        if (cameraState == CameraState.CLOSING) {
            executeAfterClosed = Runnable { doInit() }
        } else {
            doInit()
        }
        cameraState = CameraState.SETUP
        Log.d(TAG, "init() exit")
    }

    private fun doStartCapture(): Int {
        Log.d(TAG, "doStartCapture() enter")
        cameraState = CameraState.CREATESESSION
        try {
            // create camera preview request
            if (isFrontCamera) {
                captureRequestBuilder = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequestBuilder!!.addTarget(cameraFrame!!.surface)
                captureRequestBuilder!!.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, camFps)
                captureRequestBuilder!!.set(
                    CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
                )
                captureRequestBuilder!!.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                captureRequestBuilder!!.set(
                    CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY
                )
                camera!!.createCaptureSession(
                    Arrays.asList(cameraFrame!!.surface),
                    captureSessionObserver,
                    null
                )
            } else {
                captureRequestBuilder = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                captureRequestBuilder!!.addTarget(cameraFrame!!.surface)
                captureRequestBuilder!!.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, camFps)
                captureRequestBuilder!!.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                camera!!.createCaptureSession(
                    Arrays.asList(cameraFrame!!.surface),
                    captureSessionObserver,
                    null
                )
            }
        } catch (e: CameraAccessException) {
            throw Camera2Exception(e.message)
        }
        Log.d(TAG, "doStartCapture() exit")
        return 0
    }

    /**
     * Starts capturing video.
     */
    @Synchronized
    override fun startCapture(): Int {
        Log.d(
            TAG,
            "startCapture() enter (cameraState: $cameraState)"
        )
        val resume = Runnable {
            initCamera()
            scheduleStartCapture()
        }
        if (cameraState == CameraState.CLOSING) {
            executeAfterClosed = resume
        } else if (cameraState == CameraState.CLOSED) {
            resume.run()
        } else {
            scheduleStartCapture()
        }
        Log.d(TAG, "startCapture() exit")
        return 0
    }

    /**
     * Starts capturing video.
     */
    @Synchronized
    fun scheduleStartCapture(): Int {
        Log.d(
            TAG,
            "scheduleStartCapture() enter (cameraState: $cameraState)"
        )
        if (null != camera && CameraState.OPEN == cameraState) {
            return doStartCapture()
        } else if (CameraState.SETUP == cameraState) {
            Log.d(TAG, "camera not yet ready, queuing the start until camera is opened.")
            executeAfterCameraOpened = Runnable { doStartCapture() }
        } else if (CameraState.CREATESESSION == cameraState) {
            Log.d(TAG, "Camera session creation already requested")
        } else {
            Log.d(TAG, "Start Capture called before init successfully completed.")
        }
        Log.d(TAG, "scheduleStartCapture() exit")
        return 0
    }

    /**
     * Stops capturing video.
     */
    @Synchronized
    override fun stopCapture(): Int {
        Log.d(TAG, "stopCapture enter")
        if (null != camera && null != captureSession && CameraState.CLOSED != cameraState) {
            cameraState = CameraState.CLOSING
            try {
                captureSession!!.stopRepeating()
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
            captureSession!!.close()
            cameraInfoCache = null
        } else if (null != camera && CameraState.OPEN == cameraState) {
            cameraState = CameraState.CLOSING
            camera!!.close()
        } else if (CameraState.SETUP == cameraState) {
            executeAfterCameraOpened = null
        }
        Log.d(TAG, "stopCapture exit")
        return 0
    }

    /**
     * Destroys the BaseVideoCapturer object.
     */
    @Synchronized
    override fun destroy() {
        Log.d(TAG, "destroy() enter")

        /* stop display orientation polling */stopDisplayOrientationCache()

        /* stop camera message thread */stopCamThread()

        /* close ImageReader here */cameraFrame!!.close()
        Log.d(TAG, "destroy() exit")
    }

    /**
     * Whether video is being captured (true) or not (false).
     */
    override fun isCaptureStarted(): Boolean {
        return cameraState == CameraState.CAPTURE
    }

    /**
     * Returns the settings for the video capturer.
     */
    @Synchronized
    override fun getCaptureSettings(): CaptureSettings {
        val captureSettings = CaptureSettings()
        captureSettings.fps = desiredFps
        captureSettings.width = if (null != cameraFrame) cameraFrame!!.width else 0
        captureSettings.height = if (null != cameraFrame) cameraFrame!!.height else 0
        captureSettings.format = NV21
        captureSettings.expectedDelay = 0
        captureSettings.mirrorInLocalRender = isFrontCamera;
        return captureSettings
    }

    /**
     * Call this method when the activity pauses. When you override this method, implement code
     * to respond to the activity being paused. For example, you may pause capturing audio or video.
     *
     * @see .onResume
     */
    @Synchronized
    override fun onPause() {
        // PublisherKit.onPause() already calls setPublishVideo(false), which stops the camera
        // Nothing to do here
    }

    /**
     * Call this method when the activity resumes. When you override this method, implement code
     * to respond to the activity being resumed. For example, you may resume capturing audio
     * or video.
     *
     * @see .onPause
     */
    override fun onResume() {
        // PublisherKit.onResume() already calls setPublishVideo(true), which resumes the camera
        // Nothing to do here
    }

    @Throws(CameraAccessException::class)
    private fun isDepthOutputCamera(cameraId: String): Boolean {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if (capabilities != null) {
            for (capability in capabilities) {
                if (capability == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) {
                    Log.d(TAG, " REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT => TRUE")
                    return true
                }
            }
        }
        Log.d(TAG, " REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT => FALSE")
        return false
    }

    @Throws(CameraAccessException::class)
    private fun isBackwardCompatible(cameraId: String): Boolean {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if (capabilities != null) {
            for (capability in capabilities) {
                if (capability == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) {
                    Log.d(TAG, " REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE => TRUE")
                    return true
                }
            }
        }
        Log.d(TAG, " REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE => FALSE")
        return false
    }

    @Throws(CameraAccessException::class)
    private fun getCameraOutputSizes(cameraId: String): Array<Size> {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val dimMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return dimMap!!.getOutputSizes(PIXEL_FORMAT)
    }

    @get:Throws(CameraAccessException::class)
    private val nextSupportedCameraIndex: Int
        private get() {
            val cameraIds = cameraManager.cameraIdList
            val numCameraIds = cameraIds.size

            // Cycle through all the cameras to find the next one with supported
            // outputs
            for (i in 0 until numCameraIds) {
                // We use +1 so that the algorithm will rollover and check the
                // current camera too.  At minimum, the current camera *should* have
                // supported outputs.
                val nextCameraIndex = (cameraIndex + i + 1) % numCameraIds
                val outputSizes = getCameraOutputSizes(cameraIds[nextCameraIndex])
                val hasSupportedOutputs = outputSizes != null && outputSizes.size > 0

                // OPENTOK-48451. Best guess is that the crash is happening when sdk is
                // trying to open depth sensor cameras while doing cycleCamera() function.
                val isDepthOutputCamera = isDepthOutputCamera(cameraIds[nextCameraIndex])
                val isBackwardCompatible = isBackwardCompatible(cameraIds[nextCameraIndex])

                // skip camera that has same lens facing as current camera
                if ((isFrontCamera &&
                    cameraManager.getCameraCharacteristics(cameraIds[nextCameraIndex]).get(CameraCharacteristics.LENS_FACING)
                    == CameraCharacteristics.LENS_FACING_FRONT) ||
                    (!isFrontCamera &&
                            cameraManager.getCameraCharacteristics(cameraIds[nextCameraIndex]).get(CameraCharacteristics.LENS_FACING)
                            == CameraCharacteristics.LENS_FACING_BACK)
                    ) {
                        continue;
                }

                if (hasSupportedOutputs && isBackwardCompatible && !isDepthOutputCamera) {
                    return nextCameraIndex
                }
            }

            // No supported cameras found
            return -1
        }

    @Synchronized
    override fun cycleCamera() {
        try {
            val nextCameraIndex = nextSupportedCameraIndex
            val canSwapCamera = nextCameraIndex != -1

            // I think all devices *should* have at least one camera with
            // supported outputs, but adding this just in case.
            if (!canSwapCamera) {
                throw CameraAccessException(
                    CameraAccessException.CAMERA_ERROR,
                    "No cameras with supported outputs found"
                )
            }
            cameraIndex = nextCameraIndex
            swapCamera(cameraIndex)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            throw Camera2Exception(e.message)
        }
    }

    override fun getCameraIndex(): Int {
        return cameraIndex
    }

    @Synchronized
    override fun swapCamera(cameraId: Int) {
        Log.d(TAG, "swapCamera() enter")
        val oldState = cameraState
        when (oldState) {
            CameraState.CAPTURE -> stopCapture()
            CameraState.SETUP -> {}
            else -> {}
        }
        /* set camera ID */cameraIndex = cameraId
        executeAfterClosed = Runnable {
            when (oldState) {
                CameraState.CAPTURE -> {
                    initCameraFrame()
                    initCamera()
                    startCapture()
                }

                CameraState.SETUP -> {}
                else -> {}
            }
        }
        Log.d(TAG, "swapCamera() exit")
    }

    private val isFrontCamera: Boolean
        private get() = cameraInfoCache != null && cameraInfoCache!!.isFrontFacing

    private fun startCamThread() {
        Log.d(TAG, "startCamThread() enter")
        cameraThread = HandlerThread("Camera2VideoCapturer-Camera-Thread")
        cameraThread!!.start()
        cameraThreadHandler = Handler(cameraThread!!.looper)
        Log.d(TAG, "startCamThread() exit")
    }

    private fun stopCamThread() {
        Log.d(TAG, "stopCamThread() enter")
        try {
            cameraThread!!.quitSafely()
            cameraThread!!.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            // does nothing
        } finally {
            cameraThread = null
            cameraThreadHandler = null
        }
        Log.d(TAG, "stopCamThread() exit")
    }

    @Throws(CameraAccessException::class)
    private fun selectCamera(lenseDirection: Int): String? {
        for (id in cameraManager.cameraIdList) {
            val info = cameraManager.getCameraCharacteristics(id)
            /* discard cameras that don't face the right direction */if (lenseDirection == info.get(
                    CameraCharacteristics.LENS_FACING
                )
            ) {
                Log.d(
                    TAG,
                    "selectCamera() Direction the camera faces relative to device screen: " + info.get(
                        CameraCharacteristics.LENS_FACING
                    )
                )
                return id
            }
        }
        return null
    }

    @Throws(CameraAccessException::class)
    private fun selectCameraFpsRange(camId: String, fps: Int): Range<Int>? {
        for (id in cameraManager.cameraIdList) {
            if (id == camId) {
                val info = cameraManager.getCameraCharacteristics(id)
                val fpsLst: MutableCollection<Range<Int>> = ArrayList()
                Collections.addAll(
                    fpsLst,
                    *info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                )
                /* sort list by error from desired fps *
                 * Android seems to do a better job at color correction/avoid 'dark frames' issue by
                 * selecting camera settings with the smallest lower bound on allowed frame rate
                 * range. */return Collections.min(fpsLst, object : Comparator<Range<Int>> {
                    override fun compare(lhs: Range<Int>, rhs: Range<Int>): Int {
                        return calcError(lhs) - calcError(rhs)
                    }

                    private fun calcError(`val`: Range<Int>): Int {
                        return (`val`.lower + abs((`val`.upper - fps).toDouble())).toInt()
                    }
                })
            }
        }
        return null
    }

    @Throws(CameraAccessException::class)
    private fun findCameraIndex(camId: String?): Int {
        val idList = cameraManager.cameraIdList
        for (ndx in idList.indices) {
            if (idList[ndx] == camId) {
                return ndx
            }
        }
        return -1
    }

    @Throws(CameraAccessException::class)
    private fun selectPreferredSize(camId: String, width: Int, height: Int): Size {
        val outputSizeArray = getCameraOutputSizes(camId)
        val sizeLst: MutableCollection<Size> = ArrayList()
        Collections.addAll(sizeLst, *outputSizeArray)
        /* sort list by error from desired size */return Collections.min(
            sizeLst
        ) { lhs, rhs ->
            val lXerror = abs((lhs.width - width).toDouble()).toInt()
            val lYerror = abs((lhs.height - height).toDouble()).toInt()
            val rXerror = abs((rhs.width - width).toDouble()).toInt()
            val rYerror = abs((rhs.height - height).toDouble()).toInt()
            lXerror + lYerror - (rXerror + rYerror)
        }
    }

    /*
     * Set current camera orientation
     */
    private fun calculateCamRotation(): Int {
        return if (cameraInfoCache != null) {
            val cameraRotation = displayOrientationCache!!.orientation
            val cameraOrientation = cameraInfoCache!!.sensorOrientation()
            if (!cameraInfoCache!!.isFrontFacing) {
                abs(((cameraRotation - cameraOrientation) % 360).toDouble()).toInt()
            } else {
                (cameraRotation + cameraOrientation + 360) % 360
            }
        } else {
            0
        }
    }

    private fun initCameraFrame() {
        Log.d(TAG, "initCameraFrame() enter.")
        cameraFrame = try {
            val cameraIdList = cameraManager.cameraIdList
            val camId = cameraIdList[cameraIndex]
            val preferredSize = selectPreferredSize(
                camId,
                frameDimensions.width,
                frameDimensions.height
            )
            if (cameraFrame != null) cameraFrame!!.close()
            ImageReader.newInstance(
                preferredSize.width,
                preferredSize.height,
                PIXEL_FORMAT,
                3
            )
        } catch (exp: CameraAccessException) {
            throw Camera2Exception(exp.message)
        }
        Log.d(TAG, "initCameraFrame() exit.")
    }

    @SuppressLint("all")
    private fun initCamera() {
        Log.d(TAG, "initCamera() enter.")
        try {
            cameraState = CameraState.SETUP
            // find desired camera & camera ouput size
            val cameraIdList = cameraManager.cameraIdList
            val camId = cameraIdList[cameraIndex]
            camFps = selectCameraFpsRange(camId, desiredFps)
            cameraFrame!!.setOnImageAvailableListener(frameObserver, cameraThreadHandler)
            cameraInfoCache = CameraInfoCache(cameraManager.getCameraCharacteristics(camId))
            cameraManager.openCamera(camId, cameraObserver, null)
        } catch (exp: CameraAccessException) {
            throw Camera2Exception(exp.message)
        }
        Log.d(TAG, "initCamera() exit.")
    }

    private fun postAsyncException(exp: RuntimeException) {
        runtimeExceptionList.add(exp)
    }

    private fun startDisplayOrientationCache() {
        displayOrientationCache = DisplayOrientationCache(display, cameraThreadHandler)
    }

    private fun stopDisplayOrientationCache() {
        cameraThreadHandler!!.removeCallbacks(displayOrientationCache!!)
    }

    companion object {
        private const val PREFERRED_FACING_CAMERA = CameraMetadata.LENS_FACING_FRONT
        private const val PIXEL_FORMAT = ImageFormat.YUV_420_888
        private val TAG = MirrorVideoCapturer::class.java.simpleName
        private val rotationTable: SparseIntArray = object : SparseIntArray() {
            init {
                append(Surface.ROTATION_0, 0)
                append(Surface.ROTATION_90, 90)
                append(Surface.ROTATION_180, 180)
                append(Surface.ROTATION_270, 270)
            }
        }
        private val resolutionTable: SparseArray<Size?> = object : SparseArray<Size?>() {
            init {
                append(CameraCaptureResolution.LOW.ordinal, Size(352, 288))
                append(CameraCaptureResolution.MEDIUM.ordinal, Size(640, 480))
                append(CameraCaptureResolution.HIGH.ordinal, Size(1280, 720))
                append(CameraCaptureResolution.HIGH_1080P.ordinal, Size(1920, 1080))
            }
        }
        private val frameRateTable: SparseIntArray = object : SparseIntArray() {
            init {
                append(CameraCaptureFrameRate.FPS_1.ordinal, 1)
                append(CameraCaptureFrameRate.FPS_7.ordinal, 7)
                append(CameraCaptureFrameRate.FPS_15.ordinal, 15)
                append(CameraCaptureFrameRate.FPS_30.ordinal, 30)
            }
        }
    }
}