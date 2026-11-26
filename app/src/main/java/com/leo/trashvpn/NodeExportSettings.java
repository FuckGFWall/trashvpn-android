package com.leo.trashvpn;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.PersistableBundle;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Settings provides an explicit, local clipboard export, never a log or network upload. */
final class NodeExportSettings {
    private static final String PREF="wireguard-export-format";
    private final Activity activity;
    private final HomeStyle style;
    private final SharedPreferences preferences;
    private final TextView format, copy, count;
    private WarpProfile.Protocol protocol;
    private NodeExport.Format selected;
    private List<NodeSelection.Score> cached;
    private boolean copying;

    NodeExportSettings(Activity activity,LinearLayout parent,HomeStyle style,WarpProfile.Protocol protocol) {
        this.activity=activity;
        this.style=style;
        preferences=activity.getSharedPreferences(NetworkSettings.PREFS,Context.MODE_PRIVATE);
        LinearLayout section=new LinearLayout(activity);
        section.setId(R.id.settings_node_export);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionSize=new LinearLayout.LayoutParams(-1,-2);
        sectionSize.topMargin=dp(20);
        parent.addView(section,sectionSize);

        LinearLayout heading=new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPaddingRelative(dp(13),0,dp(13),dp(7));
        heading.addView(new HomeStyle.Symbol(activity,HomeStyle.Symbol.Kind.SHARE,style),dp(21),dp(21));
        TextView nodes=text(R.string.export_nodes,12,style.muted,false);
        nodes.setPaddingRelative(dp(6),0,0,0);
        heading.addView(nodes);
        section.addView(heading,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout row=new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        row.setPaddingRelative(dp(15),0,dp(7),0);
        row.setBackground(HomeStyle.shape(style.card,dp(18)));
        row.addView(text(R.string.node_export,14,style.text,false),new LinearLayout.LayoutParams(0,-2,1));

        format=text(0,13,style.orange,true);
        format.setId(R.id.settings_node_export_format);
        format.setMinHeight(dp(48));
        format.setPaddingRelative(dp(6),0,dp(6),0);
        format.setGravity(Gravity.CENTER);
        format.setOnClickListener(this::showFormats);
        row.addView(format);

        copy=text(R.string.node_export_copy,13,style.orange,true);
        copy.setId(R.id.settings_node_export_copy);
        copy.setMinHeight(dp(48));
        copy.setMinWidth(dp(48));
        copy.setPadding(dp(7),0,dp(7),0);
        copy.setGravity(Gravity.CENTER);
        copy.setOnClickListener(v->copyNodes());
        row.addView(copy);
        section.addView(row,new LinearLayout.LayoutParams(-1,-2));

        count=text(0,12,style.muted,false);
        count.setId(R.id.settings_node_export_count);
        count.setPaddingRelative(dp(13),dp(8),dp(13),0);
        section.addView(count,new LinearLayout.LayoutParams(-1,-2));
        refresh(protocol);
    }

    void refresh(WarpProfile.Protocol protocol) {
        if(this.protocol!=protocol) {
            this.protocol=protocol;
            selected=NodeExport.Format.CLASH;
            if(protocol==WarpProfile.Protocol.WG) {
                try { selected=NodeExport.Format.valueOf(preferences.getString(PREF,selected.name())); }
                catch(IllegalArgumentException ignored) { }
            }
        }
        cached=SelectionCache.loadAll(activity,protocol);
        String info=activity.getResources().getQuantityString(R.plurals.node_export_count,cached.size(),cached.size());
        format.setText(activity.getString(R.string.settings_dropdown,selected.label));
        format.setEnabled(!copying);
        count.setText(info);
        boolean canCopy=!copying && !cached.isEmpty()
                && WarpRegistration.available(activity,protocol==WarpProfile.Protocol.MASQUE);
        copy.setEnabled(canCopy);
        copy.setTextColor(canCopy?style.orange:style.disabled);
    }

    private void showFormats(View anchor) {
        if(copying) return;
        formatMenu(anchor).show();
    }

    PopupMenu formatMenu(View anchor) {
        WarpProfile.Protocol requested=protocol;
        PopupMenu menu=new PopupMenu(new ContextThemeWrapper(activity,style.dark?R.style.AppTheme:R.style.AppThemeLight),
                anchor,Gravity.END);
        NodeExport.Format[] values=NodeExport.Format.forProtocol(protocol);
        for(int i=0;i<values.length;i++) menu.getMenu().add(0,i,i,values[i].label).setChecked(values[i]==selected);
        menu.getMenu().setGroupCheckable(0,true,true);
        menu.setOnMenuItemClickListener(item->{
            if(protocol!=requested || copying) return false;
            selected=values[item.getItemId()];
            if(protocol==WarpProfile.Protocol.WG) preferences.edit().putString(PREF,selected.name()).apply();
            refresh(protocol);
            return true;
        });
        return menu;
    }

    private void copyNodes() {
        refresh(protocol);
        if(!copy.isEnabled()) return;
        copying=true;
        WarpProfile.Protocol requested=protocol;
        NodeExport.Format as=selected;
        List<NodeSelection.Score> nodes=new ArrayList<>(cached);
        Context app=activity.getApplicationContext();
        refresh(protocol);
        new Thread(()->{
            String exported=null;
            try {
                NetworkSettings settings=NetworkSettings.load(app);
                exported=NodeExport.render(requested,nodes,as,WarpRegistration.identity(app,requested),settings);
            } catch(Exception ignored) { /* Never log keys, link text or decryption errors. */ }
            String snapshot=exported;
            activity.runOnUiThread(()->{
                copying=false;
                if(activity.isDestroyed() || activity.isFinishing() || !copy.isAttachedToWindow()) return;
                refresh(protocol);
                if(protocol!=requested || !copy.isShown() || !activity.hasWindowFocus()) return;
                try {
                    if(snapshot==null || snapshot.isEmpty()) throw new IllegalStateException();
                    ClipData clip=ClipData.newPlainText(activity.getString(R.string.node_export),snapshot);
                    PersistableBundle extras=new PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE",true);
                    clip.getDescription().setExtras(extras);
                    activity.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
                    if(Build.VERSION.SDK_INT<33)
                        Toast.makeText(activity,R.string.node_export_copied,Toast.LENGTH_SHORT).show();
                } catch(RuntimeException e) {
                    Toast.makeText(activity,R.string.node_export_failed,Toast.LENGTH_SHORT).show();
                }
            });
        },"node-export").start();
    }

    private TextView text(int label,int size,int color,boolean bold) {
        TextView view=new TextView(activity);
        if(label!=0) view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        if(bold) view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return view;
    }
    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
}
