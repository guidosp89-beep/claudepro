package ghostlink.lab.android

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.ImageFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * device_profiles/<device>.json (brief §20). Only values exposed by public APIs; nothing inferred.
 * Rolling-shutter skew is not a static characteristic: the receiver records it per capture result.
 */
object DeviceProfile {
    fun deviceId(): String = "${Build.MANUFACTURER}_${Build.MODEL}".replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}".take(24)

    @Suppress("DEPRECATION")
    fun collect(ctx: Context): Map<String, Any?> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        val out = linkedMapOf<String, Any?>(
            "device_id" to deviceId(),
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "android_version" to Build.VERSION.RELEASE,
            "sdk_int" to Build.VERSION.SDK_INT,
            "soc" to if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
            "display" to linkedMapOf(
                "width_px" to metrics.widthPixels,
                "height_px" to metrics.heightPixels,
                "density_dpi" to metrics.densityDpi,
                "xdpi" to metrics.xdpi,
                "ydpi" to metrics.ydpi,
                "refresh_rate_hz" to display.refreshRate,
                "supported_modes" to display.supportedModes.map {
                    mapOf("id" to it.modeId, "w" to it.physicalWidth, "h" to it.physicalHeight, "hz" to it.refreshRate)
                },
            ),
            "cameras" to cameras(ctx),
        )
        return out
    }

    private fun cameras(ctx: Context): List<Map<String, Any?>> {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.map { id ->
            val c = cm.getCameraCharacteristics(id)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map { "${it.width}x${it.height}" } ?: emptyList()
            linkedMapOf(
                "id" to id,
                "facing" to when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> "external"
                },
                "hardware_level" to c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
                "sensor_orientation" to c.get(CameraCharacteristics.SENSOR_ORIENTATION),
                "ae_fps_ranges" to c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { "${it.lower}-${it.upper}" },
                "capabilities" to c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList(),
                "manual_sensor" to (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true),
                "exposure_time_range_ns" to c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { listOf(it.lower, it.upper) },
                "sensitivity_range" to c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { listOf(it.lower, it.upper) },
                "timestamp_source" to when (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
                    else -> "UNKNOWN"
                },
                "physical_size_mm" to c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { listOf(it.width, it.height) },
                "pixel_array" to c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { "${it.width}x${it.height}" },
                "focal_lengths_mm" to c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList(),
                "min_focus_distance_diopters" to c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
                "yuv_output_sizes" to yuvSizes.take(30),
                "max_digital_zoom" to c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM),
                "logical_multi_camera" to (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true),
            )
        }
    }
}
