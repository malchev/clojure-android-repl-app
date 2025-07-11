package com.example.clojurerepl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ClojureCodeReceiver extends BroadcastReceiver {
    private static final String TAG = "ClojureCodeReceiver";
    public static final String ACTION_EVAL_CODE = "com.example.clojurerepl.EVAL_CODE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && intent.hasExtra(MainActivity.EXTRA_CODE)) {
            String encodedCode = intent.getStringExtra(MainActivity.EXTRA_CODE);
            String encoding = intent.getStringExtra(MainActivity.EXTRA_ENCODING);

            // Decode the code if it's base64 encoded
            String decodedCode;
            if ("base64".equals(encoding) && encodedCode != null) {
                try {
                    byte[] decodedBytes = android.util.Base64.decode(encodedCode, android.util.Base64.DEFAULT);
                    decodedCode = new String(decodedBytes, "UTF-8");
                    Log.d(TAG, "Successfully decoded base64 content, length: " + decodedCode.length());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to decode base64 content", e);
                    decodedCode = encodedCode; // Fallback to original content on error
                }
            } else {
                decodedCode = encodedCode; // Use as-is if not base64 encoded
            }

            // Launch MainActivity with the decoded code
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.putExtra(RenderActivity.EXTRA_CODE, decodedCode);
            mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(mainIntent);

            Log.d(TAG, "ClojureCodeReceiver launched MainActivity with code");
        } else {
            Log.e(TAG, "Received intent without code extra");
        }
    }
}
