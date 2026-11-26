package com.leo.trashvpn;

import android.content.Context;

/** A value in a long-lived task, resolved against the current UI language when displayed. */
record UiText(int id,Object... args) {
    String get(Context context) {
        return id==0?"":args.length==0?context.getString(id):context.getString(id,args);
    }
}
