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
        Log.i(TAG, "Received broadcast intent: " + intent.getAction());
        if (ACTION_EVAL_CODE.equals(intent.getAction())) {
            // Forward the intent to MainActivity
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            mainIntent.putExtras(intent.getExtras());
            context.startActivity(mainIntent);
        }
    }
} 