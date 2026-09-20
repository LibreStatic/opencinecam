/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/** SDK-only receiver runs under test APK UID, not the instrumented app's UID/classloader. */
public final class MediaShareReceiverActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent request = getIntent();
        final ResultReceiver receiver = request.getParcelableExtra("reply");
        new Thread(() -> {
            Bundle result = new Bundle();
            result.putInt("uid", android.os.Process.myUid());
            try {
                ArrayList<Uri> uris = request.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (uris == null || request.getClipData() == null || request.getClipData().getItemCount() != uris.size())
                    throw new AssertionError("All stream grants must also be in ClipData");
                result.putInt("count", uris.size());
                for (int i = 0; i < uris.size(); i++) {
                    Uri uri = uris.get(i);
                    if (!uri.equals(request.getClipData().getItemAt(i).getUri())) throw new AssertionError("ClipData order differs");
                    result.putByteArray("bytes-" + i, read(uri));
                    boolean writeDenied = false;
                    try (ParcelFileDescriptor ignored = getContentResolver().openFileDescriptor(uri, "rw")) {
                        // Never write even if the fixture detects an accidentally writable grant.
                    } catch (SecurityException expected) { writeDenied = true; }
                    result.putBoolean("writeDenied-" + i, writeDenied);
                }
                Uri ungranted = request.getParcelableExtra("ungranted");
                boolean ungrantedDenied = false;
                try { read(ungranted); } catch (SecurityException expected) { ungrantedDenied = true; }
                result.putBoolean("ungrantedDenied", ungrantedDenied);
                result.putBoolean("success", true);
            } catch (Throwable failure) {
                result.putString("failure", android.util.Log.getStackTraceString(failure));
            }
            if (receiver != null) receiver.send(0, result);
            runOnUiThread(this::finish);
        }, "media-share-test-recipient").start();
    }
    private byte[] read(Uri uri) throws Exception {
        if (uri == null) throw new AssertionError("Missing ungranted URI fixture");
        try (InputStream stream = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (stream == null) throw new AssertionError("Missing input stream");
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) >= 0;) {
                if (count == 0 || output.size() + count > 512 * 1024) throw new AssertionError("Unexpected fixture size");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
