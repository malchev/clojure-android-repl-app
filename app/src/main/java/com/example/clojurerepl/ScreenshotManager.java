package com.example.clojurerepl;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ScreenshotManager {
    private static final String TAG = "ScreenshotManager";
    private static final String SCREENSHOT_DIR = "screenshots";

    private final Context context;

    public ScreenshotManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public File saveScreenshot(Bitmap bitmap, String fileName) {
        // Get directory for screenshots
        File screenshotDir = new File(context.getCacheDir(), SCREENSHOT_DIR);
        if (!screenshotDir.exists()) {
            if (!screenshotDir.mkdirs()) {
                Log.e(TAG, "Failed to create screenshots directory");
                return null;
            }
        }

        // Create the file
        File screenshotFile = new File(screenshotDir, fileName);

        // Save the bitmap to file
        try (FileOutputStream out = new FileOutputStream(screenshotFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            Log.d(TAG, "Screenshot saved to: " + screenshotFile.getAbsolutePath());
            return screenshotFile;
        } catch (IOException e) {
            Log.e(TAG, "Error saving screenshot", e);
            return null;
        }
    }
}
