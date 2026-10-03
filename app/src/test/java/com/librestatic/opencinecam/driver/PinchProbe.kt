package com.librestatic.opencinecam.driver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.librestatic.opencinecam.pinchToZoom

/**
 * Drives [pinchToZoom] with two driver pointers. The text recomposes on every step, as the zoom
 * readout does on the capture screen, and must not break the pinch in progress.
 */
@Composable
fun PinchProbe() {
    var ratio by remember { mutableFloatStateOf(1f) }
    var pinches by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("pinch-surface").pinchToZoom(true) { first, zoom ->
        if (first) pinches++
        ratio *= zoom
    }, contentAlignment = Alignment.Center) {
        Text("ratio=%.2f pinches=$pinches".format(ratio), color = Color.White, modifier = Modifier.testTag("pinch-readout"))
    }
}

