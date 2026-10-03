/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.ui.test.*

/*
 * A gallery card is one button, so its texts merge into it: look inside the grid with the
 * unmerged tree. Play, details, share, rename, proxy and delete sit behind the card's ⋮ menu.
 */

/** Scrolls the grid until the node tagged [tag] is composed and visible. */
internal fun SemanticsNodeInteractionsProvider.revealInGallery(tag: String): SemanticsNodeInteraction {
    onNodeWithTag("gallery-list", useUnmergedTree = true).performScrollToNode(hasTestTag(tag))
    return onNodeWithTag(tag, useUnmergedTree = true)
}

/** Opens the take's ⋮ menu and picks [action]: primary, info, share, rename, proxy or delete. */
internal fun SemanticsNodeInteractionsProvider.galleryMenuAction(takeId: String, action: String) {
    revealInGallery("gallery-menu-$takeId").performClick()
    onNodeWithTag("gallery-$action-$takeId", useUnmergedTree = true).performClick()
}

/**
 * Shows the take's details: an inspector beside the grid on wide windows, a sheet otherwise.
 * Returns true when it opened a sheet that [closeGalleryDetails] should close again.
 */
internal fun SemanticsNodeInteractionsProvider.openGalleryDetails(takeId: String): Boolean {
    galleryMenuAction(takeId, "info")
    onNodeWithTag("gallery-details-$takeId", useUnmergedTree = true).assertExists()
    return onAllNodesWithTag("gallery-info-sheet", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}

/** Closes the details sheet or clears the inspector. */
internal fun SemanticsNodeInteractionsProvider.closeGalleryDetails() {
    onNodeWithTag("gallery-info-close", useUnmergedTree = true).performClick()
}
