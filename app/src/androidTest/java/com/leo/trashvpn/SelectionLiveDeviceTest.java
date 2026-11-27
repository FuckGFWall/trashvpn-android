package com.leo.trashvpn;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in live audit: use existing enrollment, never request a VPN, and keep scores out of the user's cache. */
@RunWith(AndroidJUnit4.class)
public class SelectionLiveDeviceTest {
    private static final String TAG="SelectionAudit";

    @Test public void allProtocolsAndTiersProduceVerifiedAndRankedHttpsSamples() throws Exception {
        assumeTrue("Opt in with -e liveSelection true",
                "true".equals(InstrumentationRegistry.getArguments().getString("liveSelection")));
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Existing WG/AWG identity required",WarpRegistration.available(base,false));
        assertTrue("Existing MASQUE identity required",WarpRegistration.available(base,true));
        ConnectivityManager networks=base.getSystemService(ConnectivityManager.class);
        assertFalse("A system VPN is already up",hasVpn(networks));
        AtomicBoolean vpnSeen=new AtomicBoolean();
        ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { vpnSeen.set(true); }
        };
        networks.registerNetworkCallback(new NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(),callback);
        Map<WarpProfile.Protocol,List<NodeSelection.Score>> history=new EnumMap<>(WarpProfile.Protocol.class);
        for (WarpProfile.Protocol p:WarpProfile.Protocol.values()) history.put(p,SelectionCache.loadAll(base,p));
        List<String> failures=new ArrayList<>();
        try {
            // Start with the default tier so its live evidence is available first.
            for (ScanIntensity tier:Arrays.asList(ScanIntensity.MEDIUM,ScanIntensity.LOW,ScanIntensity.HIGH)) {
                for (WarpProfile.Protocol p:WarpProfile.Protocol.values()) {
                    assertFalse("A system VPN appeared during the audit",vpnSeen.get() || hasVpn(networks));
                    try {
                        List<NodeSelection.Score> checked=runCase(base,p,tier,history.get(p),vpnSeen);
                        // Results from earlier tiers are only hints. The current tier must measure them again.
                        history.get(p).addAll(checked);
                    } catch (Exception | AssertionError e) {
                        String reason=p+" "+tier+": "+e.getClass().getSimpleName()+(e instanceof AssertionError ? " "+e.getMessage() : "");
                        failures.add(reason);
                        Log.w(TAG,"FAILED "+reason);
                    }
                }
            }
            assertFalse("A system VPN appeared during the audit",vpnSeen.get() || hasVpn(networks));
            assertTrue(failures.toString(),failures.isEmpty());
        } finally {
            networks.unregisterNetworkCallback(callback);
        }
    }

    @SuppressWarnings("deprecation") // The explicit inventory also checks non-default VPN networks on Android 8–10.
    private static boolean hasVpn(ConnectivityManager networks) {
        for (Network net:networks.getAllNetworks()) {
            NetworkCapabilities capabilities=networks.getNetworkCapabilities(net);
            if (capabilities!=null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private static List<NodeSelection.Score> runCase(Context base,WarpProfile.Protocol p,ScanIntensity tier,
            List<NodeSelection.Score> history,AtomicBoolean vpnSeen) throws Exception {
        Map<String,SharedPreferences> fixtures=new HashMap<>();
        for (String name:Arrays.asList("node-scores-v2","selection-progress-v1",NetworkSettings.PREFS)) {
            SharedPreferences fixture=base.getSharedPreferences("qa-live-selection-"+name,Context.MODE_PRIVATE);
            assertTrue(fixture.edit().clear().commit()); // Only audit fixtures, never the regular cache/settings.
            fixtures.put(name,fixture);
        }
        Context scoped=new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                return fixtures.containsKey(name) ? fixtures.get(name) : super.getSharedPreferences(name,mode);
            }
        };
        ScanIntensity hintTier=tier==ScanIntensity.HIGH ? ScanIntensity.MEDIUM : ScanIntensity.HIGH;
        new SelectionCache(scoped,p,hintTier).save(history);
        SelectionCache cache=new SelectionCache(scoped,p,tier);
        assertTrue(cache.load().isEmpty());
        long start=SystemClock.elapsedRealtime();
        Log.i(TAG,"START "+p+" "+tier+" samples="+tier.samples+" budget="+tier.seconds+"s");
        List<NodeSelection.Score> selected=LocalSelection.run(scoped,p,tier,true,vpnSeen,(step,title,detail,percent)->{});
        long elapsed=SystemClock.elapsedRealtime()-start;
        Log.i(TAG,"DONE "+p+" "+tier+" found="+selected.size()+"/"+tier.goal+" elapsedMs="+elapsed);
        assertFalse("No fresh HTTPS trace was accepted",selected.isEmpty());
        assertTrue("Scan including cleanup took "+elapsed+" ms",elapsed<=tier.seconds*1_000L+2_000);
        List<NodeSelection.Score> restored=cache.load();
        String key=p.name()+".device"+(tier==ScanIntensity.MEDIUM ? "" : "."+tier.name());
        JSONArray saved=new JSONArray(fixtures.get("node-scores-v2").getString(key,"[]"));
        assertEquals(selected.size(),restored.size());
        assertEquals(selected.size(),saved.length());
        for (int i=0;i<restored.size();i++) {
            NodeSelection.Score score=restored.get(i);
            assertEquals(selected.get(i).endpoint,score.endpoint);
            assertEquals(score.endpoint,saved.getJSONObject(i).getString("ep"));
            assertTrue(NodeSelection.allowed(p,score.endpoint));
            assertArrayEquals(selected.get(i).samples(),score.samples());
            assertMetrics(tier,score);
            if (i>0) assertTrue(restored.get(i-1).compareTo(score)<=0);
            Log.i(TAG,String.format(Locale.ROOT,
                    "RANK %s %s %d %s %d/%d mean=%d median=%.1f p90=%d jitter=%.1f raw=%s",
                    p,tier,i+1,score.endpoint,score.successes,score.attempts,score.ms,score.medianMs,
                    score.p90Ms,score.jitterMs,Arrays.toString(score.samples())));
        }
        return selected;
    }

    private static void assertMetrics(ScanIntensity tier,NodeSelection.Score score) {
        long[] raw=score.samples();
        assertNotNull(raw);
        assertEquals(tier.samples,raw.length);
        assertEquals(raw.length,score.attempts);
        assertTrue("Delayed confirmation must have succeeded",raw[raw.length-1]>0);
        long[] ok=Arrays.stream(raw).filter(x->x>0).sorted().toArray();
        assertTrue(ok.length>=2);
        assertEquals(ok.length,score.successes);
        assertEquals((double)ok.length/raw.length,score.successRate(),1e-9);
        assertEquals(Math.round(Arrays.stream(ok).average().getAsDouble()),score.ms);
        double median=(ok[(ok.length-1)/2]+ok[ok.length/2])/2.0;
        assertEquals(median,score.medianMs,0.0);
        assertEquals(ok[(int)Math.ceil(0.9*ok.length)-1],score.p90Ms);
        double[] deviations=Arrays.stream(ok).mapToDouble(x->Math.abs(x-median)).sorted().toArray();
        assertEquals((deviations[(ok.length-1)/2]+deviations[ok.length/2])/2,score.jitterMs,0.0);
    }
}
