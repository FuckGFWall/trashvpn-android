package com.leo.trashvpn;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

final class AppHaptics {
    private AppHaptics() { }

    @SuppressWarnings("deprecation") // API 26–32 require the legacy vibrator service or audio attributes.
    static void tick(Context context) {
        if(Settings.System.getInt(context.getContentResolver(),Settings.System.HAPTIC_FEEDBACK_ENABLED,1)==0) return;
        Vibrator motor;
        if(Build.VERSION.SDK_INT>=31) {
            VibratorManager manager=context.getSystemService(VibratorManager.class);
            motor=manager==null?null:manager.getDefaultVibrator();
        } else motor=context.getSystemService(Vibrator.class);
        if(motor==null || !motor.hasVibrator()) return;
        VibrationEffect tick=VibrationEffect.createOneShot(25,
                motor.hasAmplitudeControl()?85:VibrationEffect.DEFAULT_AMPLITUDE);
        if(Build.VERSION.SDK_INT>=33) {
            motor.vibrate(tick,VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
        } else {
            AudioAttributes touch=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            motor.vibrate(tick,touch);
        }
    }
}
