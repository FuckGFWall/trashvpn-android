package com.leo.trashvpn;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class ConnectionDiagnosticsActivity extends Activity {
    private static final int EXPORT_REQUEST=21, COPY=1, EXPORT=2;
    private static final DateTimeFormatter FILE_STAMP=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss",Locale.ROOT);
    private TextView logView;
    private final Runnable refresh=this::readLog;
    private final Runnable observer=()->{
        logView.removeCallbacks(refresh);
        logView.postDelayed(refresh,120);
    };
    private ScrollView scroll;
    private HomeStyle style;
    private boolean started;
    private String displayed="", pendingExport;

    @Override protected void attachBaseContext(Context context) {
        super.attachBaseContext(AppLanguage.wrap(context));
    }

    @Override protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if(bundle!=null) pendingExport=bundle.getString("export-text");
        String theme=getSharedPreferences(NetworkSettings.PREFS,MODE_PRIVATE)
                .getString(HomeStyle.THEME_PREF,HomeStyle.DEFAULT_THEME);
        style=new HomeStyle(this,theme);

        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
        page.setBackgroundColor(style.background);
        getWindow().setStatusBarColor(style.background);
        getWindow().setNavigationBarColor(style.background);
        if(Build.VERSION.SDK_INT>=29) getWindow().setNavigationBarContrastEnforced(false);
        if(Build.VERSION.SDK_INT>=30) {
            getWindow().setDecorFitsSystemWindows(false);
            page.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets sys=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                v.setPadding(sys.left,sys.top,sys.right,sys.bottom);
                return insets;
            });
            WindowInsetsController controller=getWindow().getInsetsController();
            int flags=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if(controller!=null) controller.setSystemBarsAppearance(style.dark?0:flags,flags);
        } else getWindow().getDecorView().setSystemUiVisibility(style.dark?0:
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPaddingRelative(dp(13),0,dp(14),0);
        HomeStyle.Symbol back=button(HomeStyle.Symbol.Kind.BACK,getString(R.string.diagnostics_back));
        back.setOnClickListener(v->finish());
        header.addView(back,dp(42),dp(48));
        TextView title=new TextView(this);
        title.setText(R.string.connection_diagnostics);
        title.setTextSize(22);
        title.setTextColor(style.text);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        HomeStyle.Symbol share=button(HomeStyle.Symbol.Kind.SHARE,getString(R.string.diagnostics_share));
        share.setId(R.id.diagnostics_share);
        share.setOnClickListener(v->shareMenu(v).show());
        header.addView(share,dp(46),dp(46));
        page.addView(header,new LinearLayout.LayoutParams(-1,dp(60)));

        scroll=new ScrollView(this);
        scroll.setBackground(HomeStyle.shape(style.card,dp(18)));
        scroll.setFillViewport(true);
        logView=new TextView(this);
        logView.setId(R.id.diagnostics_text);
        logView.setPadding(dp(13),dp(12),dp(13),dp(12));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR);
        logView.setTextSize(13);
        logView.setLineSpacing(dp(3),1f);
        showLog("");
        scroll.addView(logView);
        LinearLayout.LayoutParams logArea=new LinearLayout.LayoutParams(-1,0,1);
        logArea.setMargins(dp(20),dp(4),dp(20),dp(18));
        page.addView(scroll,logArea);
        setContentView(page);
        page.requestApplyInsets();
    }

    @Override protected void onStart() {
        super.onStart();
        started=true;
        DiagnosticLog.observe(observer);
        readLog();
    }

    @Override protected void onStop() {
        started=false;
        DiagnosticLog.unobserve(observer);
        logView.removeCallbacks(refresh);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("export-text",pendingExport);
    }

    private void readLog() {
        DiagnosticLog.read(this,text->{
            if(started && !displayed.equals(text)) showLog(text);
        });
    }

    void showLog(String text) {
        boolean follow=displayed.isEmpty() || logView.getHeight()-scroll.getScrollY()<=scroll.getHeight()+dp(36);
        displayed=text;
        logView.setTextColor(text.isEmpty()?style.muted:style.text);
        logView.setText(text.isEmpty()?getString(R.string.diagnostics_empty):text);
        if(follow && !text.isEmpty()) scroll.post(()->scroll.scrollTo(0,logView.getBottom()));
    }

    PopupMenu shareMenu(View anchor) {
        PopupMenu menu=new PopupMenu(new ContextThemeWrapper(this,style.dark?R.style.AppTheme:R.style.AppThemeLight),
                anchor,Gravity.END);
        menu.getMenu().add(0,COPY,0,R.string.diagnostics_copy).setEnabled(!displayed.isEmpty());
        menu.getMenu().add(0,EXPORT,1,R.string.diagnostics_export).setEnabled(!displayed.isEmpty());
        menu.setOnMenuItemClickListener(item->{
            if(item.getItemId()==COPY) {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getString(R.string.connection_diagnostics),displayed));
                if(Build.VERSION.SDK_INT<33) toast(R.string.diagnostics_copied);
                return true;
            }
            if(item.getItemId()==EXPORT) { startExport(); return true; }
            return false;
        });
        return menu;
    }

    private void startExport() {
        pendingExport=displayed;
        Intent file=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE,"TrashVPN-diagnostics-"+FILE_STAMP.format(LocalDateTime.now())+".txt");
        try { startActivityForResult(file,EXPORT_REQUEST); }
        catch(ActivityNotFoundException e) { pendingExport=null; toast(R.string.diagnostics_no_picker); }
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=EXPORT_REQUEST) return;
        String snapshot=pendingExport;
        pendingExport=null;
        Uri destination=data==null?null:data.getData();
        if(result!=RESULT_OK || snapshot==null || destination==null) return;
        new Thread(()->{
            boolean saved=false;
            try(OutputStream out=getContentResolver().openOutputStream(destination,"wt")) {
                if(out==null) throw new IOException("No document stream");
                out.write(snapshot.getBytes(StandardCharsets.UTF_8));
                out.flush();
                saved=true;
            } catch(IOException | RuntimeException e) { saved=false; }
            boolean success=saved;
            runOnUiThread(()->{
                if(!isDestroyed()&&!isFinishing()) toast(success?R.string.diagnostics_exported:R.string.diagnostics_export_error);
            });
        },"diagnostic-export").start();
    }

    private HomeStyle.Symbol button(HomeStyle.Symbol.Kind kind,String label) {
        HomeStyle.Symbol symbol=new HomeStyle.Symbol(this,kind,style,style.tint);
        symbol.setContentDescription(label);
        symbol.setFocusable(true);
        return symbol;
    }
    private void toast(int message) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
