# Video Capturer with Background Filter

The sample application demonstrates how to utilize the device camera as a video source through custom capture using the Camera2 package. It illustrates cycling through the first available front or back camera in devices equipped with multiple cameras.

Additionally, this sample showcases the application of background blur and background replacement using the setVideoTransformers method of the publisher.

## Set up credentials
You will need a valid TokBox account for most of the sample projects. OpenTok credentials (API_KEY, SESSION_ID, TOKEN) are stored inside OpenTokConfig class. For these sample applications, credentials can be retrieved from the Dashboard and hardcoded in the application, however for a production environment server should provide these credentials (check Basic-Video-Chat project).


## Cycle the first available front or back camera
Retrieve the first available front or back camera ID by excluding IDs with the same LENS_FACING characteristic as the current camera ID.

```
    if ((isFrontCamera &&
        cameraManager.getCameraCharacteristics(cameraIds[nextCameraIndex]).get(CameraCharacteristics.LENS_FACING) 
        == CameraCharacteristics.LENS_FACING_FRONT) 
        || (!isFrontCamera &&
        cameraManager.getCameraCharacteristics(cameraIds[nextCameraIndex]).get(CameraCharacteristics.LENS_FACING) 
        == CameraCharacteristics.LENS_FACING_BACK)) {
        continue;
    }
```

## Apply Background Blur
Background Blur can be achieved by setting the name of the VideoTransformer to `BackgroundBlur`. Adjust the background blur scale by changing the background blur radius to `High`, `Low`, `None`, or `Custom`. If you set the radius property to `Custom`, add a custom_radius property to the JSON string: "{\"radius\":\"Custom\",\"custom_radius\":\"value\"}" (where custom_radius is a positive integer defining the blur radius)
```
    val backgroundBlur = publisher!!.VideoTransformer(
        "BackgroundBlur",
        "{\"radius\":\"High\"}"
    )
    videoTransformers.add(backgroundBlur)
    publisher!!.setVideoTransformers(videoTransformers)
    setBgActiveButton(bgBlurButton!!)
```

## Apply Background Replacement
Background replacement can be achieved by setting the name of the VideoTransformer to `BackgroundReplacement`. It replaces the background with the image provided at imagePath, where imagePath is the absolute file path of a local image to use as a virtual background. Supported image formats include PNG and JPEG.
```
    val backgroundReplacement: VideoTransformer = publisher!!.VideoTransformer(
        "BackgroundReplacement",
        "{\"image_file_path\":\"$imagePath\"}"
    )

    videoTransformers.add(backgroundReplacement)
    publisher!!.setVideoTransformers(videoTransformers)
    setBgActiveButton(bgVirtualButton!!)
```
