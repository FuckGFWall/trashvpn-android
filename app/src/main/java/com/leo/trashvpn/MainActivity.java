package com.leo.trashvpn;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.leo.trashvpn.R.string.*;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION=10, CONNECTED_NOTIFICATIONS=11;
    private static final int NODE_NAME_SP=14;
    private static final int REGISTRATION_STEPS=WarpRegistration.Step.values().length;
    private static final int PREPARATION_STEPS=REGISTRATION_STEPS+LocalSelection.Step.values().length;
    private final WarpVpnService.Observer observer=this::onVpnStatus;
    private final CacheBootstrap bootstrap=new CacheBootstrap();
    private final Runnable updateObserver=this::refreshUpdateNotice;
    private UpdateChecker updates;
    private SharedPreferences prefs;
    private final List<Draft> saveDrafts=new ArrayList<>();
    private final List<View> idleSettings=new ArrayList<>();
    private HomeStyle style;
    private boolean settings, visible, launching;
    private ProtocolHandoff handoff;
    private WarpVpnService.Status vpn;
    private WarpProfile.Protocol pendingProtocol;
    private ScanIntensity pendingLevel;
    private String pendingFixed;
    private boolean pendingStale;
    private FrameLayout pages;
    private View homePage, settingsPage;
    private HomeStyle.Symbol settingsGear, movingGear;
    private ValueAnimator pageMotion;
    private boolean sliding;
    private LinearLayout nodes, settingsForm;
    private View protocolControl, dnsInput, dnsLeak;
    private PowerDial power;
    private HomeStyle.Track track;
    private TextView connectionState, progressTitle, progressDetail, scanAction, protocolText, stageLeft, stageRight;
    private TextView delayAction;
    private TextView updateNotice;
    private NodeExportSettings nodeExportSettings;
    private LinearLayout localMetadata, exitMetadata;
    private TextView exitCountry, exitDataCenter, exitLatency;
    private List<String> nodeEntries=new ArrayList<>();
    private final List<LinearLayout> nodeRows=new ArrayList<>();
    private final java.util.Map<String,DelayBadge> delayBadges=new java.util.LinkedHashMap<>();
    private String paintedSelection, paintedAutomatic;
    private boolean nodesLocked;
    private int homeHint;
    private boolean scanAttempted;
    private Object settingsBackCallback;

    @Override protected void attachBaseContext(Context context) {
        super.attachBaseContext(AppLanguage.wrap(context));
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs=getSharedPreferences(NetworkSettings.PREFS,MODE_PRIVATE);
        updates=UpdateChecker.get(this);
        if (saved!=null) {
            settings=saved.getBoolean("settings");
            pendingProtocol=parseProtocol(saved.getString("pending"),null);
            pendingLevel=ScanIntensity.parse(saved.getString("pendingLevel"));
            pendingFixed=saved.getString("pendingFixed");
            pendingStale=saved.getBoolean("pendingStale");
            String switching=saved.getString("handoff");
            if(switching!=null) handoff=ProtocolHandoff.valueOf(switching);
            scanAttempted=saved.getBoolean("scanAttempted");
            homeHint=saved.getInt("homeHint");
            bootstrap.restore(saved.getStringArrayList("bootstrap"));
        }
        build();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if(settings) flushDrafts();
        build();
    }
    @Override protected void onStart() {
        super.onStart();
        WarpVpnService.observe(observer);
        ScanService.observer=this::consume;
        EnrollmentTask.observer=this::consume;
        NodeDiagnostics.observer=this::refresh;
        updates.observe(updateObserver);
    }
    @Override protected void onResume() {
        super.onResume();
        RecentsIcon.apply(this);
        visible=true;
        updates.onForeground(connected()?vpn.network:null);
        consume();
    }
    @Override protected void onPause() {
        if(settings) flushDrafts();
        visible=false;
        super.onPause();
    }
    @Override protected void onStop() {
        ScanService.observer=null;
        EnrollmentTask.observer=null;
        NodeDiagnostics.observer=null;
        WarpVpnService.unobserve(observer);
        updates.unobserve(updateObserver);
        super.onStop();
    }
    @Override protected void onDestroy() {
        updateBack(false);
        settlePages();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("settings",settings);
        if (pendingProtocol!=null) out.putString("pending",pendingProtocol.name());
        if (pendingLevel!=null) out.putString("pendingLevel",pendingLevel.name());
        out.putString("pendingFixed",pendingFixed);
        out.putBoolean("pendingStale",pendingStale);
        if(handoff!=null) out.putString("handoff",handoff.name());
        out.putBoolean("scanAttempted",scanAttempted);
        out.putInt("homeHint",homeHint);
        out.putStringArrayList("bootstrap",bootstrap.save());
    }
    @android.annotation.SuppressLint("GestureBackNavigation") // API 33+ uses the dispatcher below.
    @Override public void onBackPressed() {
        if(settings) page(false);
        else super.onBackPressed();
    }

    private static WarpProfile.Protocol parseProtocol(String name,WarpProfile.Protocol fallback) {
        if(name!=null) try { return WarpProfile.Protocol.valueOf(name); } catch(IllegalArgumentException ignored) {}
        return fallback;
    }
    private WarpProfile.Protocol protocol() { return parseProtocol(prefs.getString("protocol",null),WarpProfile.Protocol.AWG); }
    private ScanIntensity level() { return ScanIntensity.parse(prefs.getString("intensity",null)); }
    private String fixedKey() { return HotRoute.fixedKey(protocol()); }
    private String fixed() { return prefs.getString(fixedKey(),null); }
    private boolean registered(WarpProfile.Protocol p) {
        return WarpRegistration.available(this,p==WarpProfile.Protocol.MASQUE);
    }
    private boolean registering() { return EnrollmentTask.current!=null && !EnrollmentTask.current.done; }
    private boolean scanning() { return ScanService.current!=null && !ScanService.current.done; }
    private boolean vpnBusy() { return launching || (vpn!=null && vpn.busy()); }
    private boolean locked() { return registering() || scanning() || NodeDiagnostics.running() || handoff!=null || pendingProtocol!=null || vpnBusy(); }
    private boolean connected() { return vpn!=null && vpn.stage==WarpVpnService.Stage.CONNECTED; }
    private boolean switchingNode() { return vpn!=null && vpn.stage==WarpVpnService.Stage.SWITCHING; }
    private boolean canPickNode() { return !NodeDiagnostics.running() && (!locked() || connected() && handoff==null); }

    private void page(boolean open) {
        if(settings==open) return;
        settlePages();
        if(!open) {
            flushDrafts();
            boolean invalid=saveDrafts.stream().anyMatch(draft->!draft.valid);
            if(invalid) toast(s(invalid_draft));
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(settingsPage.getWindowToken(),0);
            settingsForm.requestFocus();
        }
        settings=open;
        updateBack(open);
        if(open) {
            settingsPage=makePage(true);
            pages.addView(settingsPage,new FrameLayout.LayoutParams(-1,-1));
        }
        View incoming=open?settingsPage:homePage;
        View outgoing=open?homePage:settingsPage;
        incoming.setVisibility(View.VISIBLE);
        incoming.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        outgoing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        refresh();
        float width=pages.getWidth();
        if(width<=0) { settlePages(); return; }
        sliding=true;
        prepareMovingGear();
        positionPages(open,width,0);
        pageMotion=ValueAnimator.ofFloat(0,1);
        pageMotion.setDuration(300);
        pageMotion.setInterpolator(new DecelerateInterpolator(1.25f));
        pageMotion.addUpdateListener(a->positionPages(open,width,(float)a.getAnimatedValue()));
        pageMotion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if(pageMotion==animation) {
                    pageMotion=null;
                    settlePages();
                }
            }
        });
        pageMotion.start();
    }
    private void prepareMovingGear() {
        if(settingsGear.getWidth()==0) return;
        // Keep the turning glyph visible as the incoming page passes over the home header.
        Rect bounds=new Rect(0,0,settingsGear.getWidth(),settingsGear.getHeight());
        pages.offsetDescendantRectToMyCoords(settingsGear,bounds);
        movingGear=new HomeStyle.Symbol(this,HomeStyle.Symbol.Kind.GEAR,style);
        movingGear.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        pages.getOverlay().add(movingGear);
        movingGear.layout(bounds.left,bounds.top,bounds.right,bounds.bottom);
        settingsGear.setVisibility(View.INVISIBLE);
    }
    private void positionPages(boolean opening,float width,float progress) {
        if(pages.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL) width=-width;
        float settingsFraction=opening?progress:1-progress;
        float homeOffset=-width*.27f*settingsFraction;
        homePage.setTranslationX(homeOffset);
        settingsPage.setTranslationX(width*(1-settingsFraction));
        if(movingGear!=null) {
            movingGear.setTranslationX(homeOffset);
            movingGear.setRotation(180*settingsFraction);
            movingGear.setAlpha(Math.min(1,(1-settingsFraction)*2.25f));
        }
    }
    private void settlePages() {
        sliding=false;
        ValueAnimator previous=pageMotion;
        pageMotion=null;
        if(previous!=null) previous.cancel();
        if(movingGear!=null) {
            pages.getOverlay().remove(movingGear);
            movingGear=null;
            settingsGear.setVisibility(View.VISIBLE);
        }
        homePage.setTranslationX(0);
        homePage.setVisibility(settings?View.INVISIBLE:View.VISIBLE);
        homePage.setImportantForAccessibility(settings?View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                :View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if(settingsPage!=null) {
            settingsPage.setTranslationX(0);
            if(!settings) {
                pages.removeView(settingsPage); settingsPage=null; settingsForm=null;
                saveDrafts.clear(); idleSettings.clear();
                protocolControl=null; dnsInput=null; dnsLeak=null; updateNotice=null; nodeExportSettings=null;
            }
        }
    }
    private void updateBack(boolean intercept) {
        if(Build.VERSION.SDK_INT>=33) {
            if(settingsBackCallback!=null && !intercept) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                        (android.window.OnBackInvokedCallback)settingsBackCallback);
                settingsBackCallback=null;
            } else if(settingsBackCallback==null && intercept) {
                android.window.OnBackInvokedCallback callback=()->page(false);
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);
                settingsBackCallback=callback;
            }
        }
    }
    private void build() {
        if(pages!=null) settlePages();
        updateBack(settings);
        style=new HomeStyle(this,prefs.getString(HomeStyle.THEME_PREF,HomeStyle.DEFAULT_THEME));

        pages=new FrameLayout(this) {
            @Override public boolean onInterceptTouchEvent(MotionEvent event) { return sliding; }
        };
        pages.setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
        pages.setBackgroundColor(style.background);
        homePage=makePage(false);
        pages.addView(homePage,new FrameLayout.LayoutParams(-1,-1));
        settingsPage=null;
        if(settings) {
            settingsPage=makePage(true);
            pages.addView(settingsPage,new FrameLayout.LayoutParams(-1,-1));
        }
        settlePages();
        SystemBars.setContent(this,pages,style);
        refresh();
    }

    private View makePage(boolean settingsPage) {
        LinearLayout outer=column();
        outer.setId(settingsPage?R.id.settings_page:R.id.home_page);
        outer.setBackgroundColor(style.background);
        LinearLayout header=row();
        header.setPaddingRelative(dp(settingsPage?13:20),0,dp(14),0);
        if(settingsPage) {
            HomeStyle.Symbol back=icon(HomeStyle.Symbol.Kind.BACK,s(go_home),()->page(false));
            header.addView(back,dp(42),dp(48));
        }
        TextView brand=text(settingsPage?s(R.string.settings):getString(R.string.app_name),21,style.text,true);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        if(!settingsPage) {
            settingsGear=icon(HomeStyle.Symbol.Kind.GEAR,s(R.string.settings),()->page(true));
            header.addView(settingsGear,dp(49),dp(49));
        }
        outer.addView(header,new LinearLayout.LayoutParams(-1,dp(60)));

        ScrollView scroll=new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(false);
        LinearLayout body=column();
        body.setPadding(dp(20),dp(3),dp(20),dp(22));
        scroll.addView(body);
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(settingsPage) {
            settingsForm=body;
            drawSettings();
        } else buildHome(body);
        return outer;
    }

    private void buildHome(LinearLayout body) {
        LinearLayout state=card(22);
        connectionState=text("",16,style.text,true);
        connectionState.setId(R.id.connection_state);
        connectionState.setGravity(Gravity.CENTER);
        connectionState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(state,connectionState,0);
        power=new PowerDial(this,style);
        power.setOnClickListener(v->powerPressed());
        power.setFocusable(true);
        LinearLayout.LayoutParams dial=new LinearLayout.LayoutParams(dp(144),dp(144));
        dial.gravity=Gravity.CENTER_HORIZONTAL;
        dial.topMargin=dp(8); dial.bottomMargin=dp(14);
        state.addView(power,dial);

        LinearLayout progress=row();
        progressTitle=text("",14,style.text,false);
        progressTitle.setId(R.id.preparation_status);
        progressTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress.addView(progressTitle,new LinearLayout.LayoutParams(0,-2,1));
        scanAction=text("",13,style.orange,true);
        scanAction.setGravity(Gravity.CENTER);
        scanAction.setPadding(dp(11),0,dp(11),0);
        scanAction.setMinimumHeight(dp(40));
        scanAction.setBackground(HomeStyle.shape(style.selected,dp(20)));
        scanAction.setOnClickListener(v->scanPressed());
        scanAction.setOnLongClickListener(this::showResampleOption);
        LinearLayout.LayoutParams pill=new LinearLayout.LayoutParams(-2,-2);
        pill.setMarginStart(dp(8)); progress.addView(scanAction,pill);
        add(state,progress,0);

        track=new HomeStyle.Track(this,style);
        LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,dp(20));
        bar.topMargin=dp(7); state.addView(track,bar);
        LinearLayout stages=row();
        stageLeft=text(s(registration_stage),12,style.muted,false);
        stageRight=text(s(ready_stage),12,style.muted,false);
        stageLeft.setGravity(Gravity.CENTER_HORIZONTAL);
        stageRight.setGravity(Gravity.END);
        stages.addView(stageLeft,new LinearLayout.LayoutParams(0,-2,1));
        stages.addView(stageRight,new LinearLayout.LayoutParams(0,-2,1));
        add(state,stages,0);
        progressDetail=text("",13,style.muted,false);
        add(state,progressDetail,8);

        LinearLayout metadata=column();
        localMetadata=row();
        protocolText=text("",14,style.muted,false);
        localMetadata.addView(metadataItem(HomeStyle.Symbol.Kind.SHIELD,protocolText),new LinearLayout.LayoutParams(0,-2,1));
        localMetadata.addView(text("IPv4",14,style.muted,false));
        add(metadata,localMetadata,0);
        exitMetadata=row();
        exitCountry=text("",12,style.muted,false);
        exitDataCenter=text("",12,style.muted,false);
        exitLatency=text("",12,style.dark?style.text:android.graphics.Color.BLACK,false);
        exitLatency.setId(R.id.exit_latency);
        addExitItem(HomeStyle.Symbol.Kind.GLOBE,exitCountry,.85f,true);
        addExitItem(HomeStyle.Symbol.Kind.DATACENTER,exitDataCenter,1.18f,true);
        addExitItem(HomeStyle.Symbol.Kind.PULSE,exitLatency,1f,false);
        add(metadata,exitMetadata,0);
        add(state,metadata,19);
        add(body,state,0);

        LinearLayout choices=card(19);
        LinearLayout choiceHeader=row();
        choiceHeader.addView(text(s(choose_node),19,style.text,true),new LinearLayout.LayoutParams(0,-2,1));
        delayAction=text(s(latency),13,style.orange,true);
        delayAction.setGravity(Gravity.CENTER);
        delayAction.setPadding(dp(13),0,dp(13),0);
        delayAction.setMinimumHeight(dp(40));
        delayAction.setBackground(HomeStyle.shape(style.selected,dp(20)));
        delayAction.setOnClickListener(v->delayPressed());
        choiceHeader.addView(delayAction);
        add(choices,choiceHeader,0);
        nodes=column();
        add(choices,nodes,12);
        add(body,choices,20);
        nodeEntries=new ArrayList<>();
        nodeRows.clear();
        delayBadges.clear();
        paintedSelection=null; paintedAutomatic=null;
    }

    private LinearLayout metadataItem(HomeStyle.Symbol.Kind kind,TextView value) {
        LinearLayout item=row();
        item.addView(new HomeStyle.Symbol(this,kind,style),dp(18),dp(18));
        LinearLayout.LayoutParams label=new LinearLayout.LayoutParams(-2,-2);
        label.setMarginStart(dp(8));
        item.addView(value,label);
        return item;
    }

    private void addExitItem(HomeStyle.Symbol.Kind kind,TextView value,float weight,boolean spaced) {
        LinearLayout item=row();
        item.addView(new HomeStyle.Symbol(this,kind,style),dp(15),dp(15));
        LinearLayout.LayoutParams label=new LinearLayout.LayoutParams(0,-2,1);
        label.setMarginStart(dp(4));
        item.addView(value,label);
        LinearLayout.LayoutParams column=new LinearLayout.LayoutParams(0,-2,weight);
        if(spaced) column.setMarginEnd(dp(5));
        exitMetadata.addView(item,column);
    }

    private void showMetadata(WarpProfile.Protocol protocol,ExitLocation location) {
        localMetadata.setVisibility(location==null?View.VISIBLE:View.GONE);
        exitMetadata.setVisibility(location==null?View.GONE:View.VISIBLE);
        if(location==null) {
            protocolText.setText(pretty(protocol));
        } else {
            String unknown=getString(R.string.exit_location_unknown);
            exitCountry.setText(getString(R.string.exit_country,location.countryCode.isEmpty()?unknown:location.flag()));
            exitDataCenter.setText(getString(R.string.exit_data_center,location.dataCenter.isEmpty()?unknown:location.dataCenter));
            exitLatency.setText(exitDelayText(connectedDelay(currentNode()),unknown));
        }
    }

    private WarpVpnService.ActiveNode currentNode() {
        return (connected()||switchingNode()) && vpn.node!=null && vpn.node.protocol()==protocol()?vpn.node:null;
    }

    private String selectedNode(WarpVpnService.ActiveNode active) {
        return active==null?fixed():active.automatic()?null:active.endpoint();
    }

    private List<String> visibleNodes(List<NodeSelection.Score> cached,WarpVpnService.ActiveNode active) {
        List<String> entries=HotRoute.visibleEndpoints(protocol(),fixed(),cached);
        // A cache entry can expire while its tunnel is still connected.
        if(active!=null && !entries.contains(active.endpoint())) entries.add(active.endpoint());
        return entries;
    }

    private static String nodeName(WarpProfile.Protocol protocol,int index) {
        return String.format(Locale.ROOT,"%s%02d",protocol.name(),index+1);
    }

    private void renderNodes(boolean lock,List<NodeSelection.Score> cached) {
        WarpVpnService.ActiveNode active=currentNode();
        List<String> entries=visibleNodes(cached,active);
        String selected=selectedNode(active);
        String automatic=active!=null && active.automatic()?active.endpoint():null;
        WarpProfile.Protocol protocol=protocol();
        if(!nodeRows.isEmpty() && entries.equals(nodeEntries) && java.util.Objects.equals(selected,paintedSelection)
                && java.util.Objects.equals(automatic,paintedAutomatic)
                && lock==nodesLocked) {
            renderDelays();
            return;
        }
        nodes.removeAllViews();
        nodeEntries=entries; paintedSelection=selected; paintedAutomatic=automatic; nodesLocked=lock; nodeRows.clear();
        delayBadges.clear();
        for(int i=-1;i<entries.size();i++) {
            String endpoint=i<0?null:entries.get(i);
            String name=i<0?s(auto_select):nodeName(protocol,i);
            boolean checked=java.util.Objects.equals(endpoint,selected);
            LinearLayout line=row();
            line.setPadding(dp(13),dp(8),dp(13),dp(8));
            line.setBackground(HomeStyle.shape(lock?style.tile:checked?style.selected:style.tile,dp(13)));
            HomeStyle.Symbol circle=new HomeStyle.Symbol(this,HomeStyle.Symbol.Kind.CHECK,style);
            circle.checked(checked);
            line.addView(circle,dp(23),dp(23));
            TextView label=text(name,NODE_NAME_SP,checked&&!lock?style.orange:style.text,true);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
            lp.setMarginStart(dp(12)); line.addView(label,lp);
            String description=name;
            if(endpoint==null && automatic!=null) {
                String actual=nodeName(protocol,entries.indexOf(automatic));
                TextView badge=text(actual,NODE_NAME_SP,style.orange,true);
                badge.setPaddingRelative(dp(8),0,0,0);
                line.addView(badge);
                description=getString(R.string.active_auto_node,actual);
            } else if(endpoint!=null) {
                DelayBadge badge=new DelayBadge();
                line.addView(badge,new LinearLayout.LayoutParams(dp(80),dp(27)));
                delayBadges.put(endpoint,badge);
            }
            line.setMinimumHeight(dp(44));
            line.setContentDescription(description);
            line.setSelected(checked);
            line.setOnClickListener(v->chooseNode(endpoint));
            line.setEnabled(!lock);
            line.setAlpha(lock&&!NodeDiagnostics.running()&&!switchingNode()?.47f:1f);
            add(nodes,line,i<0?0:9); nodeRows.add(line);
        }
        renderDelays();
    }

    private void chooseNode(String endpoint) {
        if(!canPickNode() || java.util.Objects.equals(selectedNode(currentNode()),endpoint)) return;
        AppHaptics.tick(this);
        if(connected()) {
            startService(new Intent(this,WarpVpnService.class).setAction(WarpVpnService.SWITCH)
                    .putExtra(WarpVpnService.PROTOCOL,protocol().name()).putExtra(WarpVpnService.FIXED_ENDPOINT,endpoint));
        } else {
            prefs.edit().putString(fixedKey(),endpoint).apply();
            homeHint=0; refresh();
        }
    }

    private void delayPressed() {
        if(NodeDiagnostics.running() || !(connected() || !locked())) return;
        if(!connected() && NodeDiagnostics.hasSystemVpn(this)) {
            refresh();
            toast(s(disconnect_other_vpn));
            return;
        }
        WarpProfile.Protocol p=protocol();
        ScanIntensity l=level();
        if(!registered(p)) { toast(s(registration_required)); return; }
        List<String> targets=visibleNodes(new SelectionCache(this,p,l).load(),currentNode());
        boolean online=connected();
        NodeDiagnostics.Job job=new NodeDiagnostics.Job(p,l,targets,online,currentNode());
        NodeDiagnostics.current=job;
        refresh();
        if(online) {
            try { startService(new Intent(this,WarpVpnService.class).setAction(WarpVpnService.DELAY)); }
            catch(RuntimeException e) { NodeDiagnostics.finish(this,job); }
        } else NodeDiagnostics.startOffline(this,job);
    }

    private void renderDelays() {
        boolean possible=connected() || !locked()&&!NodeDiagnostics.hasSystemVpn(this);
        delayAction.setEnabled(possible&&!NodeDiagnostics.running());
        delayAction.setAlpha(delayAction.isEnabled()?1f:.46f);
        NodeDiagnostics.Job job=NodeDiagnostics.current;
        boolean same=job!=null && job.protocol==protocol() && job.level==level();
        WarpVpnService.ActiveNode active=currentNode();
        for(java.util.Map.Entry<String,DelayBadge> entry:delayBadges.entrySet()) {
            String ep=entry.getKey();
            Long result=same?job.results.get(ep):null;
            if(active!=null && ep.equals(active.endpoint())) result=connectedDelay(active);
            entry.getValue().show(result,same&&!job.done&&job.endpoints.contains(ep));
        }
    }

    // Share the measurement between the exit row and the actual active node's row.
    private Long connectedDelay(WarpVpnService.ActiveNode active) {
        if(active==null) return null;
        NodeDiagnostics.Job job=NodeDiagnostics.current;
        if(job!=null && job.activeNode==active) {
            Long result=job.results.get(active.endpoint());
            if(result!=null) return result;
        }
        return vpn.latencyMs!=0?vpn.latencyMs:null;
    }

    private String formatDelay(long result) {
        return result<0?"-1":getString(R.string.node_latency,result);
    }

    private CharSequence exitDelayText(Long result,String unknown) {
        String value=result==null?unknown:formatDelay(result);
        String label=getString(R.string.exit_latency,value);
        int start=label.lastIndexOf(value);
        if(start<0 || value.isEmpty()) return label;
        SpannableString styled=new SpannableString(label);
        int color=result==null?style.muted:delayColor(result);
        styled.setSpan(new ForegroundColorSpan(color),start,start+value.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private int delayColor(long result) {
        return result<0?(style.dark?0xffff6b66:0xffbc2724):(style.dark?0xff60dc91:0xff187b42);
    }

    private final class DelayBadge extends FrameLayout {
        final TextView number;
        final ProgressBar spinner;
        DelayBadge() {
            super(MainActivity.this);
            number=text("",12,style.muted,true);
            number.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
            addView(number,new FrameLayout.LayoutParams(-1,-1));
            spinner=new ProgressBar(MainActivity.this,null,android.R.attr.progressBarStyleSmall);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(style.orange));
            spinner.setContentDescription(s(testing_latency));
            addView(spinner,new FrameLayout.LayoutParams(dp(17),dp(17),Gravity.END|Gravity.CENTER_VERTICAL));
        }
        void show(Long result,boolean waiting) {
            spinner.setVisibility(waiting&&result==null?View.VISIBLE:View.GONE);
            number.setVisibility(result==null?View.GONE:View.VISIBLE);
            if(result!=null) {
                number.setText(formatDelay(result));
                number.setTextColor(delayColor(result));
            }
        }
    }

    private void drawSettings() {
        NetworkSettings config=NetworkSettings.load(this);
        saveDrafts.clear();
        idleSettings.clear();
        settingsForm.setFocusableInTouchMode(true);
        settingsForm.requestFocus();

        updateNotice=text("",14,style.orange,true);
        updateNotice.setId(R.id.settings_update);
        updateNotice.setVisibility(View.GONE);
        updateNotice.setMinimumHeight(dp(48));
        updateNotice.setGravity(Gravity.CENTER_VERTICAL);
        updateNotice.setPaddingRelative(dp(17),dp(10),dp(17),dp(10));
        updateNotice.setBackground(HomeStyle.shape(style.selected,dp(14)));
        updateNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        updateNotice.setOnClickListener(v->openDownload());
        add(settingsForm,updateNotice,0);
        refreshUpdateNotice();

        LinearLayout protocols=group(s(connection_protocol),0);
        protocolControl=dropdown(protocols,s(tunnel_protocol),protocol().ordinal(),new String[]{"AWG","WireGuard","MASQUE"},
                i->switchProtocol(WarpProfile.Protocol.values()[i]));
        LinearLayout appearance=group(s(R.string.appearance),14);
        String theme=prefs.getString(HomeStyle.THEME_PREF,HomeStyle.DEFAULT_THEME);
        dropdown(appearance,s(R.string.theme),"light".equals(theme)?0:"dark".equals(theme)?1:2,
                new String[]{s(theme_light),s(theme_dark),s(theme_system)},i->{
                    flushDrafts();
                    prefs.edit().putString(HomeStyle.THEME_PREF,new String[]{"light","dark",HomeStyle.DEFAULT_THEME}[i]).apply();
                    build();
                });
        AppLanguage[] languages=AppLanguage.values();
        String[] languageNames=new String[languages.length];
        for(int i=0;i<languages.length;i++) languageNames[i]=s(languages[i].label);
        View languageMenu=dropdown(appearance,s(language),AppLanguage.current(this).ordinal(),languageNames,
                i->setLanguage(languages[i]));
        languageMenu.setId(R.id.settings_language);

        LinearLayout selection=group(s(R.string.selection),14);
        add(selection,text(s(intensity),14,style.text,false),12);
        ScanIntensity[] intensities=ScanIntensity.values();
        TextView[] tickLabels=new TextView[intensities.length];
        TextView description=text("",12,style.muted,false);
        for(int i=0;i<tickLabels.length;i++) {
            TextView t=text(s(intensities[i].label),12,style.muted,false);
            t.setGravity(i==0?Gravity.START:i==tickLabels.length-1?Gravity.END:Gravity.CENTER);
            t.setPadding(dp(12),dp(6),dp(12),dp(6));
            tickLabels[i]=t;
        }
        IntensitySlider slider=new IntensitySlider(this,level(),draft->{
            description.setText(draft.description(this));
            for(int i=0;i<tickLabels.length;i++)
                tickLabels[i].setTextColor(draft==intensities[i]?style.orange:style.muted);
        },selected->{
            prefs.edit().putString("intensity",selected.name()).apply();
            AppHaptics.tick(this);
            resetHome();
            refresh();
        });
        slider.setId(R.id.settings_intensity);
        slider.setContentDescription(s(intensity));
        idleSettings.add(slider);
        slider.setSplitTrack(false);
        slider.setProgressTintList(ColorStateList.valueOf(style.tint));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(style.track));
        slider.setThumbTintList(ColorStateList.valueOf(style.tint));
        add(selection,slider,2);
        LinearLayout ticks=row();
        for(int i=0;i<tickLabels.length;i++) {
            ScanIntensity choice=intensities[i];
            TextView t=tickLabels[i];
            t.setOnClickListener(v->slider.select(choice));
            idleSettings.add(t);
            ticks.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        }
        add(selection,ticks,1);
        idleSettings.add(edit(selection,s(probe_timeout),s(seconds),"",Integer.toString(config.probeSeconds),true,value->
                prefs.edit().putInt(NetworkSettings.TIMEOUT,NetworkSettings.timeout(value)).apply()));
        add(selection,description,9);
        Switch auto=toggle(selection,s(auto_connect_after_scan),autoConnect());
        auto.setId(R.id.settings_auto_connect);
        auto.setOnCheckedChangeListener((button,checked)->
                prefs.edit().putBoolean(NetworkSettings.AUTO_CONNECT,checked).apply());

        LinearLayout network=group(s(R.string.network),14);
        idleSettings.add(edit(network,"MTU","","",Integer.toString(config.mtu),true,value->
                prefs.edit().putInt(NetworkSettings.MTU,NetworkSettings.mtu(value)).apply()));
        add(network,text(s(auto_mtu_hint),12,style.muted,false),7);

        LinearLayout dns=group("DNS",14);
        EditText custom=edit(dns,s(custom_dns),"",s(default_dns_hint),config.customDns,false,
                value->prefs.edit().putString(NetworkSettings.DNS,NetworkSettings.dns(value)).apply());
        Switch leak=toggle(dns,s(prevent_dns_leaks),config.protect);
        dnsInput=custom; dnsLeak=leak;
        leak.setOnCheckedChangeListener((button,checked)->prefs.edit().putBoolean(NetworkSettings.PROTECT,checked).apply());
        Switch noDns=toggle(dns,s(no_tunnel_dns),config.noDns);
        idleSettings.add(noDns);
        TextView dnsHint=text(s(no_tunnel_dns_hint),12,style.muted,false);
        dnsHint.setVisibility(config.noDns?View.VISIBLE:View.GONE);
        add(dns,dnsHint,6);
        noDns.setOnCheckedChangeListener((button, checked)->{
            if(checked && custom.hasFocus()) custom.clearFocus();
            prefs.edit().putBoolean(NetworkSettings.NO_DNS,checked).apply();
            updateSettingsControls(locked());
            dnsHint.setVisibility(checked?View.VISIBLE:View.GONE);
        });
        nodeExportSettings=new NodeExportSettings(this,settingsForm,style,protocol());
        SettingsAbout.add(this,settingsForm,style);
    }
    private void refreshUpdateNotice() { showAvailableUpdate(updates.availableTag()); }
    void showAvailableUpdate(String tag) {
        if(updateNotice==null || !settings) return;
        updateNotice.setVisibility(tag==null?View.GONE:View.VISIBLE);
        if(tag!=null) updateNotice.setText(getString(update_available,tag));
        LinearLayout.LayoutParams space=(LinearLayout.LayoutParams)updateNotice.getLayoutParams();
        space.bottomMargin=tag==null?0:dp(14);
        updateNotice.setLayoutParams(space);
    }
    private void openDownload() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(UpdateChecker.DOWNLOAD))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch(ActivityNotFoundException e) { toast(s(no_link_app)); }
    }
    void setLanguage(AppLanguage requested) {
        String tag=requested.locale.toLanguageTag();
        if(tag.equals(prefs.getString(AppLanguage.PREF,null))) return;
        if(settings) flushDrafts();
        boolean changed=requested!=AppLanguage.current(this);
        prefs.edit().putString(AppLanguage.PREF,tag).apply();
        if(changed) {
            WarpVpnService.refreshNotificationLanguage();
            recreate();
        }
    }
    private void updateSettingsControls(boolean busy) {
        if(!settings || protocolControl==null) return;
        if(nodeExportSettings!=null) nodeExportSettings.refresh(protocol());
        protocolControl.setEnabled(!registering() && pendingProtocol==null);
        protocolControl.setAlpha(protocolControl.isEnabled()?1f:.45f);
        for(View control:idleSettings) control.setEnabled(!busy);
        boolean dnsEnabled=!busy && !prefs.getBoolean(NetworkSettings.NO_DNS,false);
        dnsInput.setEnabled(dnsEnabled);
        dnsLeak.setEnabled(dnsEnabled);
    }

    private LinearLayout group(String title,int gap) {
        LinearLayout card=card(17);
        add(card,text(title,16,style.text,true),0);
        add(settingsForm,card,gap);
        return card;
    }
    private View dropdown(LinearLayout parent,String title,int current,String[] choices,
                          java.util.function.IntConsumer action) {
        LinearLayout line=row();
        line.setContentDescription(title);
        line.setPadding(dp(12),dp(10),dp(12),dp(10));
        line.setMinimumHeight(dp(48));
        line.setBackground(HomeStyle.shape(style.tile,dp(12)));
        line.addView(text(title,14,style.text,false),new LinearLayout.LayoutParams(0,-2,1));
        TextView value=text(getString(R.string.settings_dropdown,choices[current]),13,style.orange,false);
        line.addView(value);
        final int[] index={current};
        line.setOnClickListener(v->{
            ContextThemeWrapper context = new ContextThemeWrapper(this,style.dark?R.style.AppTheme:R.style.AppThemeLight);
            PopupMenu menu=new PopupMenu(context,line,Gravity.END);
            for(int i=0;i<choices.length;i++) menu.getMenu().add(0,i,i,choices[i]).setChecked(i==index[0]);
            menu.getMenu().setGroupCheckable(0,true,true);
            menu.setOnMenuItemClickListener(item->{
                if(!line.isEnabled()) return false;
                index[0]=item.getItemId();
                value.setText(getString(R.string.settings_dropdown,choices[index[0]]));
                action.accept(index[0]); return true;
            });
            menu.show();
        });
        add(parent,line,10);
        return line;
    }

    private final class Draft implements Runnable {
        final EditText field;
        final java.util.function.Consumer<String> save;
        boolean valid=true;
        Draft(EditText field,java.util.function.Consumer<String> save) { this.field=field; this.save=save; }
        @Override public void run() {
            if(!field.isEnabled()) return;
            try {
                save.accept(field.getText().toString());
                valid=true; field.setError(null);
            } catch(IllegalArgumentException e) {
                valid=false;
                int id=field.getId();
                field.setError(s(id==R.id.settings_dns?invalid_dns:id==R.id.settings_timeout?invalid_timeout:invalid_mtu));
            }
        }
    }
    private void flushDrafts() { for(Runnable draft:saveDrafts) draft.run(); }

    private EditText edit(LinearLayout parent,String title,String unit,String hint,String value,boolean number,
                          java.util.function.Consumer<String> save) {
        LinearLayout line=number?row():column();
        TextView label=text(title,14,style.text,false);
        EditText input=new EditText(this);
        int id = !number ? R.id.settings_dns : unit.isEmpty() ? R.id.settings_mtu : R.id.settings_timeout;
        input.setId(id);
        input.setContentDescription(title);
        label.setLabelFor(id);
        input.setSingleLine(true);
        input.setInputType(number?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextSize(13);
        input.setTextColor(style.text);
        input.setHintTextColor(style.muted);
        input.setHint(hint);
        input.setText(value);
        input.setPadding(dp(11),dp(5),dp(11),dp(5));
        input.setMinimumHeight(dp(36));
        input.setBackground(HomeStyle.shape(style.tile,dp(9)));
        Draft draft=new Draft(input,save);
        saveDrafts.add(draft);
        input.setOnFocusChangeListener((v,focus)->{ if(!focus) draft.run(); });
        input.setOnEditorActionListener((v,action,event)->{
            if(action!=EditorInfo.IME_ACTION_DONE) return false;
            draft.run();
            if(draft.valid) {
                getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);
                settingsForm.requestFocus();
            }
            return true;
        });
        if(number) {
            line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            line.addView(input,new LinearLayout.LayoutParams(dp(90),-2));
            if(!unit.isEmpty()) {
                TextView suffix=text(unit,12,style.muted,false);
                suffix.setPaddingRelative(dp(8),0,0,0);
                line.addView(suffix);
            }
        } else { add(line,label,0); add(line,input,7); }
        add(parent,line,11);
        return input;
    }

    private Switch toggle(LinearLayout parent,String title,boolean checked) {
        Switch control=new Switch(new ContextThemeWrapper(this,style.dark?R.style.AppTheme:R.style.AppThemeLight));
        control.setText(title);
        control.setTextColor(style.text);
        control.setTextSize(13);
        control.setChecked(checked);
        control.setMinimumHeight(dp(46));
        control.setThumbTintList(new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled},{android.R.attr.state_checked},{}},
                new int[]{style.disabled,style.tint,style.muted}));
        add(parent,control,5);
        return control;
    }
    private void refresh() {
        if(power==null) return;
        boolean register=registering(), scan=scanning(), blocked=locked();
        boolean connected=connected()||switchingNode();
        boolean connecting=launching || (vpn!=null&&(vpn.stage==WarpVpnService.Stage.STARTING||vpn.stage==WarpVpnService.Stage.VERIFYING));
        boolean stopping=vpn!=null&&vpn.stage==WarpVpnService.Stage.STOPPING;
        WarpProfile.Protocol chosen=protocol();
        boolean enrolled=registered(chosen);
        List<NodeSelection.Score> cached=enrolled?new SelectionCache(this,chosen,level()).load():java.util.Collections.emptyList();
        boolean ready=!cached.isEmpty() && !scan && !register;
        power.setEnabled(!register&&!scan&&handoff==null&&pendingProtocol==null&&!stopping
                && (!NodeDiagnostics.running()||connected));
        power.show(connected?PowerDial.State.CONNECTED:connecting?PowerDial.State.CONNECTING:PowerDial.State.IDLE);
        power.setContentDescription(connected||connecting?s(disconnect_vpn):s(connect_vpn));
        scanAction.setEnabled(scan ? handoff==null && !ScanService.current.cancel.get() : !blocked);
        scanAction.setAlpha(scanAction.isEnabled()?1f:.43f);
        connectionState.setText(connected?R.string.connected:connecting?R.string.connecting:R.string.disconnected);
        connectionState.setTextColor(connected||connecting?style.orange:style.muted);
        String title="", detail="";
        int percent=ready?100:enrolled?HomeStyle.Track.REGISTRATION:0;
        String badge=scanAttempted||!cached.isEmpty()?s(scan_again):s(scan_action);
        boolean canResume=!blocked && chosen==WarpProfile.Protocol.MASQUE && enrolled
                && ScanJournal.canContinue(this,chosen,level());
        if (canResume) badge=s(scan_resume);
        scanAction.setLongClickable(canResume);
        HomeStyle.Track.Running running=HomeStyle.Track.Running.NONE;
        if(handoff!=null) {
            title=s(switching_protocol);
            if(!connecting && !stopping) detail=pretty(chosen);
        } else if(register) {
            EnrollmentTask t=EnrollmentTask.current;
            title=getString(preparation_step_format,t.step.ordinal()+1,PREPARATION_STEPS,
                    s(t.step.label),s(t.title));
            detail=s(registering);
            percent=3+t.step.ordinal()*9;
            running=HomeStyle.Track.Running.REGISTER;
        } else if(scan) {
            ScanService.Job j=ScanService.current;
            title=selectionTitle(j.step,j.title);
            detail=j.detail.get(this);
            percent=HomeStyle.Track.REGISTRATION+(100-HomeStyle.Track.REGISTRATION)*j.percent/100;
            badge=j.cancel.get()?s(pausing):s(pause);
            if(!j.cancel.get()) running=HomeStyle.Track.Running.SELECT;
        } else if(connecting||switchingNode()) {
            title=launching?s(vpn_starting):vpn.title.get(this);
        } else if(homeHint!=0 && !connected && !stopping) {
            title=homeHint==scan_finished || homeHint==scan_no_nodes
                    ? selectionTitle(LocalSelection.Step.SUMMARY,homeHint) : s(homeHint);
        }
        scanAction.setText(badge);
        int lines=connecting?1:Integer.MAX_VALUE;
        if(progressTitle.getMaxLines()!=lines) {
            progressTitle.setMaxLines(lines);
            progressTitle.setEllipsize(connecting?TextUtils.TruncateAt.END:null);
        }
        progressTitle.setText(title);
        progressDetail.setText(detail);
        progressDetail.setVisibility(detail.isEmpty()?View.GONE:View.VISIBLE);
        track.update(percent,enrolled,ready,running);
        stageLeft.setTextColor(ready?style.readyText:register||enrolled?style.orange:style.muted);
        stageRight.setTextColor(ready?style.readyText:style.muted);
        showMetadata(chosen,connected?vpn.location:null);
        renderNodes(!canPickNode(),cached);
        updateSettingsControls(blocked);
    }

    private String selectionTitle(LocalSelection.Step step,int title) {
        return getString(preparation_step_format,REGISTRATION_STEPS+step.ordinal()+1,PREPARATION_STEPS,
                s(step.label),s(title));
    }

    private void powerPressed() {
        if(registering()||scanning()||handoff!=null||pendingProtocol!=null
                || (NodeDiagnostics.running()&&!vpnBusy())) return;
        if(vpnBusy()) {
            stopVpn();
            return;
        }
        perform(EnrollmentTask.Action.CONNECT,fixed(),protocol(),level());
    }
    private void stopVpn() {
        startService(new Intent(this,WarpVpnService.class).setAction(WarpVpnService.STOP));
    }
    void switchProtocol(WarpProfile.Protocol target) {
        if(target==protocol() || registering() || pendingProtocol!=null || NodeDiagnostics.running()) return;
        if(settings) flushDrafts();
        boolean scan=scanning(), tunnel=vpnBusy();
        prefs.edit().putString("protocol",target.name()).apply();
        resetHome();
        if(handoff==null && (scan||tunnel))
            handoff=tunnel?ProtocolHandoff.RECONNECT:ProtocolHandoff.SELECT;
        if(scan) ScanService.current.cancel.set(true);
        if(tunnel && (vpn==null || vpn.stage!=WarpVpnService.Stage.STOPPING)) stopVpn();
        consume();
    }
    private void scanPressed() {
        if(handoff!=null) return;
        if(scanning()) { ScanService.current.cancel.set(true); refresh(); return; }
        if(!locked()) {
            WarpProfile.Protocol p=protocol();
            ScanIntensity l=level();
            boolean resample=p==WarpProfile.Protocol.MASQUE && !ScanJournal.canContinue(this,p,l);
            perform(resample?EnrollmentTask.Action.RESCAN:EnrollmentTask.Action.SCAN,null,p,l);
        }
    }
    private boolean showResampleOption(View anchor) {
        WarpProfile.Protocol p=protocol();
        ScanIntensity l=level();
        if (locked() || p!=WarpProfile.Protocol.MASQUE || !registered(p) || !ScanJournal.canContinue(this,p,l))
            return false;
        android.widget.PopupMenu menu=new android.widget.PopupMenu(this,anchor,Gravity.END);
        menu.getMenu().add(scan_again).setOnMenuItemClickListener(item->{
            if (!locked() && p==protocol() && l==level()) perform(EnrollmentTask.Action.RESCAN,null,p,l);
            return true;
        });
        menu.show();
        return true;
    }
    private boolean autoConnect() { return prefs.getBoolean(NetworkSettings.AUTO_CONNECT,false); }
    private void bootstrapMissingCache() {
        if(!visible || locked() || CacheBootstrap.suspendedForInstrumentation) return;
        WarpProfile.Protocol protocol=protocol();
        ScanIntensity level=level();
        boolean registered=registered(protocol);
        boolean cached=registered && !new SelectionCache(this,protocol,level).load().isEmpty();
        if(bootstrap.claim(protocol,level,cached)) perform(EnrollmentTask.Action.SCAN,null,protocol,level);
    }

    private void perform(EnrollmentTask.Action action,String endpoint,WarpProfile.Protocol p,ScanIntensity l) {
        homeHint=0;
        if(!registered(p)) {
            bootstrap.mark(p,l);
            EnrollmentTask.start(this,new EnrollmentTask(p==WarpProfile.Protocol.MASQUE,action,endpoint,p,l));
            return;
        }
        if(action!=EnrollmentTask.Action.CONNECT || (endpoint==null && new SelectionCache(this,p,l).load().isEmpty()))
            startScan(new ScanService.Job(p,l,action==EnrollmentTask.Action.RESCAN));
        else requestVpn(p,l,endpoint,endpoint==null);
    }
    private void startScan(ScanService.Job j) {
        bootstrap.mark(j.protocol,j.level);
        ScanService.begin(this,j);
    }
    void onVpnStatus(WarpVpnService.Status state) {
        launching=false;
        vpn=state;
        if(state.stage==WarpVpnService.Stage.FAILED && handoff==null) {
            homeHint=state.title.id();
            if(visible) toast(state.detail.get(this));
        }
        if(state.stage==WarpVpnService.Stage.CONNECTED && state.detail.id()==switch_rejected && visible)
            toast(state.detail.get(this));
        consume();
    }
    private void consume() {
        if(visible) {
            EnrollmentTask task=EnrollmentTask.current;
            if(task!=null && task.done) {
                bootstrap.mark(task.protocol,task.level);
                EnrollmentTask.current=null;
                if(task.error!=null) { homeHint=registration_failed; toast(task.error.get(this)); }
                else perform(task.action,task.endpoint,task.protocol,task.level);
            }
            ScanService.Job j=ScanService.current;
            if(j!=null && j.done) {
                bootstrap.mark(j.protocol,j.level);
                ScanService.current=null;
                boolean current=handoff==null && j.protocol==protocol() && j.level==level();
                if(current) {
                    scanAttempted=true;
                    homeHint=j.title;
                }
                if(ProtocolHandoff.connectAfterScan(autoConnect(),j.found,j.cancel.get(),current))
                    requestVpn(j.protocol,j.level,null,false);
            }
            if(vpn!=null && vpn.retryScan!=null) {
                ScanService.Job retry=vpn.retryScan;
                WarpVpnService.acknowledgeScan(vpn);
                vpn=null;
                if(handoff==null && retry.protocol==protocol() && retry.level==level()) startScan(retry);
            }
            if(handoff!=null && !scanning() && !vpnBusy()) {
                WarpProfile.Protocol p=protocol();
                ScanIntensity l=level();
                ProtocolHandoff.Next next=handoff.next(registered(p) && !new SelectionCache(this,p,l).load().isEmpty());
                handoff=null;
                if(next==ProtocolHandoff.Next.SCAN) perform(EnrollmentTask.Action.SCAN,null,p,l);
                if(next==ProtocolHandoff.Next.CONNECT) perform(EnrollmentTask.Action.CONNECT,fixed(),p,l);
            }
        }
        bootstrapMissingCache();
        refresh();
        maybeRequestConnectedNotifications();
    }

    private void maybeRequestConnectedNotifications() {
        if(Build.VERSION.SDK_INT<33 || !visible || !connected() || vpn.network==null
                || prefs.getBoolean("vpn-notification-requested",false)) return;
        String permission=android.Manifest.permission.POST_NOTIFICATIONS;
        if(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
                || getSystemService(NotificationManager.class).areNotificationsEnabled()) return;
        prefs.edit().putBoolean("vpn-notification-requested",true).apply();
        requestPermissions(new String[]{permission},CONNECTED_NOTIFICATIONS);
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==CONNECTED_NOTIFICATIONS && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)
            WarpVpnService.refreshNotificationLanguage();
    }

    private void requestVpn(WarpProfile.Protocol p,ScanIntensity l,String endpoint,boolean retry) {
        if(settings) flushDrafts();
        pendingProtocol=p; pendingLevel=l; pendingFixed=endpoint; pendingStale=retry;
        Intent consent=VpnService.prepare(this);
        if(consent!=null) { refresh(); startActivityForResult(consent,VPN_PERMISSION); }
        else launchVpn();
    }
    private void launchVpn() {
        if(pendingProtocol==null) return;
        Intent intent=new Intent(this,WarpVpnService.class).setAction(WarpVpnService.START)
                .putExtra(WarpVpnService.PROTOCOL,pendingProtocol.name())
                .putExtra(WarpVpnService.INTENSITY,pendingLevel.name())
                .putExtra(WarpVpnService.CANDIDATE,NodeSelection.AUTO)
                .putExtra(WarpVpnService.FIXED_ENDPOINT,pendingFixed)
                .putExtra(WarpVpnService.SCAN_IF_STALE,pendingStale);
        pendingProtocol=null; launching=true; homeHint=0;
        try { startService(intent); }
        catch(RuntimeException e) { launching=false; homeHint=vpn_launch_blocked; }
        refresh();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==VPN_PERMISSION) {
            if(result==RESULT_OK) launchVpn();
            else { pendingProtocol=null; homeHint=vpn_permission_denied; refresh(); }
        }
    }

    private String s(int id) { return getString(id); }
    private void resetHome() { homeHint=0; scanAttempted=false; }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    private static String pretty(WarpProfile.Protocol p) {
        return switch(p) { case AWG -> "AmneziaWG"; case WG -> "WireGuard"; case MASQUE -> "MASQUE"; };
    }
    private LinearLayout column() {
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    private LinearLayout row() {
        LinearLayout l=new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }
    private LinearLayout card(int pad) {
        LinearLayout l=column();
        l.setPadding(dp(pad),dp(pad),dp(pad),dp(pad));
        l.setBackground(HomeStyle.shape(style.card,dp(25))); return l;
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if(bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private HomeStyle.Symbol icon(HomeStyle.Symbol.Kind kind,String name,Runnable click) {
        HomeStyle.Symbol shape=new HomeStyle.Symbol(this,kind,style);
        shape.setOnClickListener(v->click.run()); shape.setContentDescription(name);
        shape.setFocusable(true); return shape;
    }
    private void add(LinearLayout parent, View child,int gap) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(gap); parent.addView(child,p);
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
