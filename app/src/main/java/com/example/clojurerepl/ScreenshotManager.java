package com.example.clojurerepl;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenshotManager {
    private static final String TAG = "ScreenshotManager";
    private static final String SCREENSHOT_DIR = "screenshots";

    private final Context context;
    private final File screenshotDir;

    public ScreenshotManager(Context context) {
        this.context = context.getApplicationContext();
        this.screenshotDir = new File(context.getCacheDir(), SCREENSHOT_DIR);
        if (!screenshotDir.exists()) {
            screenshotDir.mkdirs();
        }
    }

    public File captureScreenshot(View view) {
        try {
            // Create bitmap of the view
            view.setDrawingCacheEnabled(true);
            Bitmap bitmap = Bitmap.createBitmap(view.getDrawingCache());
            view.setDrawingCacheEnabled(false);

            // Create file with timestamp
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
            File screenshotFile = new File(screenshotDir,
                "screenshot_" + timestamp + ".png");

            // Save bitmap to file
            FileOutputStream fos = new FileOutputStream(screenshotFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();

            Log.d(TAG, "Screenshot saved to: " + screenshotFile.getAbsolutePath());
            return screenshotFile;

        } catch (IOException e) {
            Log.e(TAG, "Error capturing screenshot", e);
            return null;
        }
    }

    public void clearScreenshots() {
        File[] files = screenshotDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }
}
