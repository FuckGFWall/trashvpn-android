package com.leo.trashvpn;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.BitmapFactory;

final class RecentsIcon {
    private RecentsIcon() { }

    @SuppressWarnings("deprecation") // Some launchers only look at the task bitmap.
    static void apply(Activity activity) {
        activity.setTaskDescription(new ActivityManager.TaskDescription(activity.getString(R.string.app_name),
                BitmapFactory.decodeResource(activity.getResources(),R.mipmap.ic_recents),0));
    }
}
