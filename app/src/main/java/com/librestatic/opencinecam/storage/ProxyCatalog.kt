/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

/** Original fields are historical references, not a statement of current presence or integrity.
 * The receipt digest binds even non-visible technical fields to the selected revision. */
internal data class ProxyCatalogEntry(val takeId: String, val originalDisplayName: String,
    val result: MediaProxyResult, val receiptSha256: String)
