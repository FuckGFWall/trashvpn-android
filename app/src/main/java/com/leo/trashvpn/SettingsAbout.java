package com.leo.trashvpn;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import static com.leo.trashvpn.HomeStyle.Symbol.Kind.*;

final class SettingsAbout {
    private record Link(String title,String url,HomeStyle.Symbol.Kind kind,int color) { }
    private static final Link[] LINKS={
            new Link(null,"https://itmanager.top",GLOBE,0xffffa114),
            new Link("GitHub","https://github.com/FuckGFWall/trashvpn-android",GITHUB,0xfffafafa),
            new Link("Telegram","https://t.me/+eJ2kUZ3kUJ9jZmNl",TELEGRAM,0xff168df6),
            new Link("YouTube","https://www.youtube.com/@TrashVPN",YOUTUBE,0xffff3c37),
            new Link("X","https://x.com/trashvpn",BRAND_X,0xfffafafa)
    };
    private static final String DONATE="https://itmanager.top/donate.html";
    private final Activity activity;
    private final HomeStyle style;

    static void add(Activity activity,LinearLayout parent,HomeStyle style) {
        new SettingsAbout(activity,style).build(parent);
    }

    private SettingsAbout(Activity activity,HomeStyle style) { this.activity=activity; this.style=style; }

    private void build(LinearLayout parent) {
        LinearLayout about=section(parent,activity.getString(R.string.about),style.card);
        about.setId(R.id.settings_about);
        for(Link link:LINKS) {
            int color=style.dark?link.color:link.kind==GITHUB||link.kind==BRAND_X?0xff252528:link.color;
            String label=link.title==null?activity.getString(R.string.website):link.title;
            about.addView(row(label,link.kind,color,EXTERNAL,null,false,()->open(link.url)));
            divider(about,59);
        }
        about.addView(row(activity.getString(R.string.version),null,0,null,version(),false,null));
        divider(about,14);
        about.addView(row(activity.getString(R.string.connection_diagnostics),PULSE,style.tint,NEXT,null,false,()->
                ConnectionDiagnosticsActivity.open(activity)));

        String donateLabel=activity.getString(R.string.donate);
        LinearLayout donate=section(parent,donateLabel,0xffcc3506);
        donate.setId(R.id.settings_donate);
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xffce3805,0xffb91f0c});
        gradient.setCornerRadius(dp(18));
        donate.setBackground(gradient);
        donate.addView(row(donateLabel,HEART,Color.WHITE,EXTERNAL,null,true,()->open(DONATE)));
    }

    private LinearLayout section(LinearLayout parent,String title,int background) {
        TextView label=text(title,12,style.muted,false);
        label.setPaddingRelative(dp(14),0,0,dp(7));
        LinearLayout.LayoutParams header=new LinearLayout.LayoutParams(-1,-2);
        header.topMargin=dp(20);
        parent.addView(label,header);
        LinearLayout card=new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(HomeStyle.shape(background,dp(18)));
        card.setClipToOutline(true);
        parent.addView(card,new LinearLayout.LayoutParams(-1,-2));
        return card;
    }

    private LinearLayout row(String name,HomeStyle.Symbol.Kind icon,int ink,HomeStyle.Symbol.Kind arrow,
                             String detail,boolean emphasized,Runnable onClick) {
        LinearLayout row=new LinearLayout(activity);
        row.setPadding(dp(14),dp(6),dp(14),dp(6));
        row.setMinimumHeight(dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL);
        if(icon!=null) {
            FrameLayout badge=new FrameLayout(activity);
            if(icon!=PULSE) {
                int badgeColor=emphasized?0x35ffffff:icon==GITHUB||icon==BRAND_X
                        ?(style.dark?0xff303034:0xffe4e4e8):(ink&0xffffff)|0x24_000000;
                badge.setBackground(HomeStyle.shape(badgeColor,dp(8)));
            }
            HomeStyle.Symbol symbol=new HomeStyle.Symbol(activity,icon,style,ink);
            badge.addView(symbol,new FrameLayout.LayoutParams(dp(icon==PULSE?30:21),dp(icon==PULSE?30:21),Gravity.CENTER));
            LinearLayout.LayoutParams iconSize=new LinearLayout.LayoutParams(dp(31),dp(31));
            iconSize.setMarginEnd(dp(13));
            row.addView(badge,iconSize);
        }
        row.addView(text(name,14,emphasized?Color.WHITE:style.text,emphasized),
                new LinearLayout.LayoutParams(0,-2,1));
        if(detail!=null) row.addView(text(detail,13,style.muted,false));
        if(arrow!=null) row.addView(new HomeStyle.Symbol(activity,arrow,style,emphasized?Color.WHITE:style.muted),
                dp(20),dp(20));
        if(onClick!=null) {
            row.setContentDescription(name);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(emphasized?0x35ffffff:
                    style.dark?0x28ffffff:0x22000000),null,HomeStyle.shape(Color.WHITE,0)));
            row.setOnClickListener(v->onClick.run());
            row.setFocusable(true);
        }
        return row;
    }

    private void divider(LinearLayout card,int inset) {
        View line=new View(activity);
        line.setBackgroundColor(style.track);
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,1);
        size.setMarginStart(dp(inset));
        card.addView(line,size);
    }

    @SuppressWarnings("deprecation") // API 26–27 expose versionCode as an int.
    private String version() {
        try {
            PackageInfo pkg=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0);
            return pkg.versionName+" ("+(Build.VERSION.SDK_INT>=28?pkg.getLongVersionCode():pkg.versionCode)+")";
        } catch(PackageManager.NameNotFoundException e) {
            return "—";
        }
    }

    private void open(String link) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(link))); }
        catch(ActivityNotFoundException e) { Toast.makeText(activity,R.string.no_link_app,Toast.LENGTH_SHORT).show(); }
    }

    private TextView text(String value,int size,int color,boolean bold) {
        TextView view=new TextView(activity);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if(bold) view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return view;
    }
    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
}
