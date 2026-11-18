package me.venkatesh.saveobsidian;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import java.io.DataOutputStream;
import java.io.IOException;

/** One worker process and engine per PDF. Binder serializes the one-way calls for this worker. */
public final class PdfVisionService extends Service {
    static final int DESCRIBE = IBinder.FIRST_CALL_TRANSACTION, PLAN = DESCRIBE + 1, TEXT = PLAN + 1;
    private PdfVisionEngine engine;

    private final Binder worker = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (android.os.Build.VERSION.SDK_INT < 35 || code != DESCRIBE && code != PLAN && code != TEXT) return false;
            try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.CREATOR.createFromParcel(data));
                 var sink = new DataOutputStream(output)) {
                String result;
                try {
                    if (engine == null) engine = new PdfVisionEngine(PdfVisionService.this);
                    android.util.Log.i("Sparkbox", "local PDF worker " + (code == DESCRIBE ? "reading figure" : "planning from text"));
                    if (code == DESCRIBE) {
                        try (var image = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.CREATOR.createFromParcel(data))) {
                            byte[] bytes = image.readNBytes(PdfRenderService.MAX_JPEG + 1);
                            if (bytes.length > PdfRenderService.MAX_JPEG) throw new IOException("PDF figure exceeds the size limit");
                            result = engine.describe(bytes, data.readInt() != 0);
                        }
                    } else if (code == TEXT) {
                        result = engine.text(data.readString(), data.readInt());
                    } else {
                        var plan = engine.plan(data.readString(), data.readString(), data.readString(), data.readString(), data.readString());
                        if (plan == null) throw new IOException("Local PDF model returned no usable notes.");
                        result = plan.toString();
                    }
                    if (result.length() > 20_000) throw new IOException("Local PDF model returned an oversized reply.");
                } catch (Exception | LinkageError failed) {
                    android.util.Log.w("Sparkbox", "Local PDF inference failed", failed);
                    sink.writeBoolean(false);
                    sink.writeUTF(failed instanceof IOException && failed.getMessage() != null ? failed.getMessage() : "The local PDF model failed.");
                    return true;
                }
                sink.writeBoolean(true);
                sink.writeUTF(result);
            } catch (IOException failed) {
                android.util.Log.w("Sparkbox", "Local PDF worker reply closed", failed);
            }
            return true;
        }
    };

    @Override public IBinder onBind(Intent intent) { return worker; }

    @Override public void onDestroy() {
        super.onDestroy();
        // Never release native state concurrently with inference. This also frees a wedged engine.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
