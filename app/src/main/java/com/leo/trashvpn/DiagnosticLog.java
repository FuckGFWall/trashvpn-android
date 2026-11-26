package com.leo.trashvpn;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** An app-owned history of explicit status messages, not a copy of logcat or trace bodies. */
final class DiagnosticLog {
    enum Topic {
        SELECTION("SCAN"), CONNECTION("VPN"), LATENCY("LATENCY");
        final String label;
        Topic(String label) { this.label=label; }
    }

    static final int MAX_LINES=350, MAX_BYTES=96*1024;
    private static final String TAG="TrashVPN";
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS",Locale.ROOT);
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService DISK=Executors.newSingleThreadScheduledExecutor();
    private static final Set<Runnable> observers=new LinkedHashSet<>(); // main thread
    private static History history; // disk executor only
    private static boolean writeQueued;

    private DiagnosticLog() { }

    static void info(Context context,Topic topic,String message) {
        Log.i(TAG,message);
        record(context,topic,message);
    }
    static void warn(Context context,Topic topic,String message) {
        Log.w(TAG,message);
        record(context,topic,message);
    }

    private static void record(Context context,Topic topic,String message) {
        String stamp=STAMP.withZone(ZoneId.systemDefault()).format(Instant.now());
        String safeLine=message.replace('\r',' ').replace('\n',' ');
        String line=stamp+"  ["+topic.label+"] "+safeLine.substring(0,Math.min(280,safeLine.length()));
        AtomicFile file=file(context);
        DISK.execute(()->{
            load(file).add(line);
            if(!writeQueued) {
                writeQueued=true;
                DISK.schedule(()->{
                    writeQueued=false;
                    write(file);
                },200,TimeUnit.MILLISECONDS);
            }
            MAIN.post(()->new ArrayList<>(observers).forEach(Runnable::run));
        });
    }

    static void observe(Runnable observer) { observers.add(observer); }
    static void unobserve(Runnable observer) { observers.remove(observer); }
    static void read(Context context,Consumer<String> receive) {
        AtomicFile file=file(context);
        DISK.execute(()->{
            String text=load(file).text();
            MAIN.post(()->receive.accept(text));
        });
    }

    private static AtomicFile file(Context c) {
        return new AtomicFile(new File(c.getNoBackupFilesDir(),"connection-diagnostics.txt"));
    }

    private static History load(AtomicFile file) {
        if(history!=null) return history;
        history=new History();
        try(FileInputStream in=file.openRead()) {
            for(String line:new String(KeyStoreFile.readBounded(in,MAX_BYTES),StandardCharsets.UTF_8).split("\n"))
                if(!line.isEmpty()) history.add(line);
        } catch(FileNotFoundException ignored) {
            // No events have been recorded yet.
        } catch(IOException e) {
            Log.w(TAG,"Could not read diagnostic history",e);
        }
        return history;
    }

    private static void write(AtomicFile file) {
        FileOutputStream out=null;
        try {
            out=file.startWrite();
            out.write(history.text().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch(IOException e) {
            file.failWrite(out);
            Log.w(TAG,"Could not save diagnostic history",e);
        }
    }

    static final class History {
        private record Line(String text,int bytes) { }
        private final ArrayDeque<Line> entries=new ArrayDeque<>();
        private int bytes;
        void add(String line) {
            int length=line.getBytes(StandardCharsets.UTF_8).length+1;
            if(length>MAX_BYTES) return;
            entries.addLast(new Line(line,length));
            bytes+=length;
            while(entries.size()>MAX_LINES || bytes>MAX_BYTES) bytes-=entries.removeFirst().bytes;
        }
        String text() {
            StringBuilder out=new StringBuilder();
            for(Line line:entries) out.append(line.text).append('\n');
            return out.toString();
        }
    }
}
