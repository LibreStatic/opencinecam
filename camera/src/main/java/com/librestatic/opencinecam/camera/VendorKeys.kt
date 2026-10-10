package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** How a vendor key was found on one camera. */
enum class VendorKeyResolution { ADVERTISED, BY_NAME, ABSENT }

/**
 * The resolution rule for a vendor key, on plain name sets. An advertised key always wins. Where
 * the HAL leaves a vendor tag out of availableRequestKeys/availableResultKeys (the Razr Fold's
 * Qualcomm tags), the key is constructed by name, but only on API 29+ (the public Key
 * constructors) and only when the feature's static [gateKeyName] is in the camera's
 * characteristics: a device without the vendor section is never touched.
 */
fun resolveVendorKey(
    keyName: String,
    gateKeyName: String,
    advertisedNames: Set<String>,
    staticKeyNames: Set<String>,
    sdkInt: Int,
): VendorKeyResolution = when {
    keyName in advertisedNames -> VendorKeyResolution.ADVERTISED
    sdkInt >= Build.VERSION_CODES.Q && gateKeyName in staticKeyNames -> VendorKeyResolution.BY_NAME
    else -> VendorKeyResolution.ABSENT
}

/** Resolves vendor request/result keys of a camera by name; see [resolveVendorKey]. */
internal object VendorKeys {
    private const val TAG = "VendorKeys"
    private val logged = ConcurrentHashMap.newKeySet<String>()

    private fun resolution(
        characteristics: CameraCharacteristics, name: String, gate: String, result: Boolean,
    ): VendorKeyResolution {
        val advertised: Set<String> = if (result) {
            characteristics.availableCaptureResultKeys.mapTo(HashSet()) { it.name }
        } else {
            characteristics.availableCaptureRequestKeys.mapTo(HashSet()) { it.name }
        }
        return resolveVendorKey(
            name, gate,
            advertised,
            characteristics.keys.mapTo(HashSet()) { it.name },
            Build.VERSION.SDK_INT,
        )
    }

    private fun noteByName(cameraId: String?, name: String) {
        if (logged.add("$cameraId/$name")) {
            Log.i(TAG, "Vendor key $name resolved by name (not advertised) camera=$cameraId")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> request(
        characteristics: CameraCharacteristics, cameraId: String?, name: String, gate: String, type: Class<T>,
    ): CaptureRequest.Key<T>? = when (resolution(characteristics, name, gate, false)) {
        VendorKeyResolution.ADVERTISED ->
            characteristics.availableCaptureRequestKeys.firstOrNull { it.name == name } as CaptureRequest.Key<T>?
        VendorKeyResolution.BY_NAME -> runCatching { CaptureRequest.Key(name, type) }.getOrNull()
            ?.also { noteByName(cameraId, name) }
        VendorKeyResolution.ABSENT -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> result(
        characteristics: CameraCharacteristics, cameraId: String?, name: String, gate: String, type: Class<T>,
    ): CaptureResult.Key<T>? = when (resolution(characteristics, name, gate, true)) {
        VendorKeyResolution.ADVERTISED ->
            characteristics.availableCaptureResultKeys.firstOrNull { it.name == name } as CaptureResult.Key<T>?
        VendorKeyResolution.BY_NAME -> runCatching { CaptureResult.Key(name, type) }.getOrNull()
            ?.also { noteByName(cameraId, name) }
        VendorKeyResolution.ABSENT -> null
    }
}
