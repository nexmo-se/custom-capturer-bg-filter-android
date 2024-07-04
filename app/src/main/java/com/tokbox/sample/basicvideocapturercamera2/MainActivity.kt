package com.tokbox.sample.basicvideocapturercamera2

import android.Manifest
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.opentok.android.BaseVideoRenderer
import com.opentok.android.OpentokError
import com.opentok.android.Publisher
import com.opentok.android.PublisherKit
import com.opentok.android.PublisherKit.PublisherListener
import com.opentok.android.PublisherKit.VideoTransformer
import com.opentok.android.Session
import com.opentok.android.Session.SessionListener
import com.opentok.android.Stream
import com.opentok.android.Subscriber
import com.opentok.android.SubscriberKit
import com.tokbox.sample.basicvideocapturercamera2.OpenTokConfig.description
import com.tokbox.sample.basicvideocapturercamera2.OpenTokConfig.isValid
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import pub.devrel.easypermissions.EasyPermissions.PermissionCallbacks
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class MainActivity : AppCompatActivity(), PermissionCallbacks {
    private var session: Session? = null
    private var publisher: Publisher? = null
    private var subscriber: Subscriber? = null
    private var publisherViewContainer: RelativeLayout? = null
    private var subscriberViewContainer: LinearLayout? = null
    private var cycleCameraButton: Button? = null
    private var bgNoneButton: Button? = null
    private var bgBlurButton: Button? = null
    private var bgVirtualButton: Button? = null
    private var videoCapturer:MirrorVideoCapturer? = null
    private val videoTransformers = ArrayList<VideoTransformer>()


    private val publisherListener: PublisherListener = object : PublisherListener {
        override fun onStreamCreated(publisherKit: PublisherKit, stream: Stream) {
            Log.d(TAG, "onStreamCreated: Own stream " + stream.streamId + " created")
        }

        override fun onStreamDestroyed(publisherKit: PublisherKit, stream: Stream) {
            Log.d(TAG, "onStreamDestroyed: Own stream " + stream.streamId + " destroyed")
        }

        override fun onError(publisherKit: PublisherKit, opentokError: OpentokError) {
            finishWithMessage("PublisherKit error: " + opentokError.message)
        }
    }
    private val sessionListener: SessionListener = object : SessionListener {
        override fun onConnected(session: Session) {
            Log.d(TAG, "onConnected: Connected to session " + session.sessionId)
            publisher = Publisher.Builder(this@MainActivity)
                .capturer(
                    videoCapturer
                )
                .build()
            publisher!!.setPublisherListener(publisherListener)
            publisher!!.setStyle(
                BaseVideoRenderer.STYLE_VIDEO_SCALE,
                BaseVideoRenderer.STYLE_VIDEO_FILL
            )
            publisherViewContainer!!.addView(publisher!!.view)
            if (publisher!!.view is GLSurfaceView) {
                (publisher!!.view as GLSurfaceView).setZOrderOnTop(true)
            }
            session.publish(publisher)
        }

        override fun onDisconnected(session: Session) {
            Log.d(TAG, "onDisconnected: disconnected from session " + session.sessionId)
            this@MainActivity.session = null
        }

        override fun onError(session: Session, opentokError: OpentokError) {
            finishWithMessage("Session error: " + opentokError.message)
        }

        override fun onStreamReceived(session: Session, stream: Stream) {
            Log.d(
                TAG,
                "onStreamReceived: New stream " + stream.streamId + " in session " + session.sessionId
            )
            if (subscriber != null) {
                return
            }
            subscribeToStream(stream)
        }

        override fun onStreamDropped(session: Session, stream: Stream) {
            Log.d(
                TAG,
                "onStreamDropped: Stream " + stream.streamId + " dropped from session " + session.sessionId
            )
            if (subscriber == null) {
                return
            }
            if (subscriber!!.stream == stream) {
                subscriberViewContainer!!.removeView(subscriber!!.view)
                subscriber = null
            }
        }
    }
    private val videoListener: SubscriberKit.VideoListener = object : SubscriberKit.VideoListener {
        override fun onVideoDataReceived(subscriberKit: SubscriberKit) {
            subscriber!!.setStyle(
                BaseVideoRenderer.STYLE_VIDEO_SCALE,
                BaseVideoRenderer.STYLE_VIDEO_FILL
            )
            subscriberViewContainer!!.addView(subscriber!!.view)
        }

        override fun onVideoDisabled(subscriberKit: SubscriberKit, s: String) {}
        override fun onVideoEnabled(subscriberKit: SubscriberKit, s: String) {}
        override fun onVideoDisableWarning(subscriberKit: SubscriberKit) {}
        override fun onVideoDisableWarningLifted(subscriberKit: SubscriberKit) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (!isValid) {
            finishWithMessage("Invalid OpenTokConfig. " + description)
            return
        }
        publisherViewContainer = findViewById(R.id.publisherview)
        subscriberViewContainer = findViewById(R.id.subscriberview)
        cycleCameraButton = findViewById(R.id.cycleCamera)
        bgNoneButton = findViewById(R.id.bgNone)
        bgBlurButton = findViewById(R.id.bgBlur)
        bgVirtualButton = findViewById(R.id.bgVirtual)

        videoCapturer = MirrorVideoCapturer(
            this@MainActivity, Publisher.CameraCaptureResolution.HIGH,
            Publisher.CameraCaptureFrameRate.FPS_30
        )
        cycleCameraButton!!.setOnClickListener {
            if (publisher != null) {
                videoCapturer!!.cycleCamera()
            }
        }
        bgNoneButton!!.setOnClickListener {
            if(publisher !== null) {
                // Clear video transformer
                videoTransformers.clear();
                publisher!!.setVideoTransformers(videoTransformers);

                setBgActiveButton(bgNoneButton!!)


            }
        }
        bgBlurButton!!.setOnClickListener {
            if(publisher !== null) {
                videoTransformers.clear();
                val backgroundBlur = publisher!!.VideoTransformer(
                    "BackgroundBlur",
                    "{\"radius\":\"High\"}"
                )
                videoTransformers.add(backgroundBlur)
                publisher!!.setVideoTransformers(videoTransformers)
                setBgActiveButton(bgBlurButton!!)
            }
        }
        bgVirtualButton!!.setOnClickListener {
            if(publisher !== null) {
                videoTransformers.clear();

                val resourceName = try {
                    this.resources.getResourceEntryName(R.drawable.background)  // Assuming "beach" is the name of the drawable resource
                } catch (e: Resources.NotFoundException) {
                    return@setOnClickListener  // Return if the resource ID is not found
                }

                val bitmap = BitmapFactory.decodeResource(
                    resources,
                    R.drawable.background
                ) // Assuming "beach" is the name of the drawable resource

                val imageFile = File(this.filesDir, "$resourceName.jpg")

                try {
                    FileOutputStream(imageFile).use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                        outputStream.flush()
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    return@setOnClickListener
                }

                val imagePath = imageFile.absolutePath

                val backgroundReplacement: VideoTransformer = publisher!!.VideoTransformer(
                    "BackgroundReplacement",
                    "{\"image_file_path\":\"$imagePath\"}"
                )

                videoTransformers.add(backgroundReplacement)
                publisher!!.setVideoTransformers(videoTransformers)
                setBgActiveButton(bgVirtualButton!!)
            }
        }

        requestPermissions()
    }

    override fun onPause() {
        super.onPause()
        if (session == null) {
            return
        }
        session!!.onPause()
        if (isFinishing) {
            disconnectSession()
        }
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            return
        }
        session!!.onResume()
    }

    override fun onDestroy() {
        disconnectSession()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsGranted(requestCode: Int, perms: List<String>) {
        Log.d(
            TAG,
            "onPermissionsGranted:$requestCode: $perms"
        )
    }

    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        finishWithMessage("onPermissionsDenied: $requestCode: $perms")
    }

    @AfterPermissionGranted(PERMISSIONS_REQUEST_CODE)
    private fun requestPermissions() {
        val perms = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (EasyPermissions.hasPermissions(this, *perms)) {
            Log.d(TAG, "session config ${OpenTokConfig.API_KEY}")
            session = Session.Builder(this, OpenTokConfig.API_KEY, OpenTokConfig.SESSION_ID).build()
            session!!.setSessionListener(sessionListener)
            session!!.connect(OpenTokConfig.TOKEN)
        } else {
            EasyPermissions.requestPermissions(
                this,
                getString(R.string.rationale_video_app),
                PERMISSIONS_REQUEST_CODE,
                *perms
            )
        }
    }

    private fun subscribeToStream(stream: Stream) {
        subscriber = Subscriber.Builder(this, stream).build()
        subscriber!!.setVideoListener(videoListener)
        session!!.subscribe(subscriber)
    }

    private fun disconnectSession() {
        if (session == null) {
            return
        }
        if (subscriber != null) {
            subscriberViewContainer!!.removeView(subscriber!!.view)
            session!!.unsubscribe(subscriber)
            subscriber = null
        }
        if (publisher != null) {
            publisherViewContainer!!.removeView(publisher!!.view)
            session!!.unpublish(publisher)
            publisher = null
        }
        session!!.disconnect()
    }

    private fun setBgActiveButton(activeButton: Button) {
        // Clear all button
        bgNoneButton!!.setBackgroundResource(R.color.colorInactive)
        bgBlurButton!!.setBackgroundResource(R.color.colorInactive)
        bgVirtualButton!!.setBackgroundResource(R.color.colorInactive)

        // set active button
        activeButton.setBackgroundResource(R.color.colorActive)
    }
    private fun finishWithMessage(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val PERMISSIONS_REQUEST_CODE = 124
    }
}
