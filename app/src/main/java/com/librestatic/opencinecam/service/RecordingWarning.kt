/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

/**
 * The running take's warning line (`recording-warning`): the admission warning, the software
 * encoder, the HEVC-to-H.264 fallback, then the live notices; null when there is nothing to say.
 */
internal fun recordingWarningLine(admission: String?, softwareEncoder: String?, codecFallback: String?, notices: List<String>): String? =
    (listOfNotNull(admission, softwareEncoder, codecFallback) + notices).joinToString(" · ").ifEmpty { null }
