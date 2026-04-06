package com.qf.soundeffect;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class SoundActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Launch your real app
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.radiorubka.wdsp");
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
