package com.leo.trashvpn;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Installs an inset-aware activity root and applies matching system bar colors. */
final class SystemBars {
    private SystemBars() {}

    static void setContent(Activity activity,View root,HomeStyle style) {
        Window window=activity.getWindow();
        if(Build.VERSION.SDK_INT>=30) root.setOnApplyWindowInsetsListener((view,insets)->{
            Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom);
            return insets;
        });

        // This creates the decor before PhoneWindow.getInsetsController() tries to use it.
        activity.setContentView(root);
        window.setStatusBarColor(style.background);
        window.setNavigationBarColor(style.background);
        if(Build.VERSION.SDK_INT>=29) window.setNavigationBarContrastEnforced(false);
        if(Build.VERSION.SDK_INT>=30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller=window.getInsetsController();
            int flags=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    |WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if(controller!=null) controller.setSystemBarsAppearance(style.dark?0:flags,flags);
            root.requestApplyInsets();
        } else window.getDecorView().setSystemUiVisibility(style.dark?0:
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
}
