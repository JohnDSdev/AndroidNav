package com.johndsdev.androidnav;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.app.Instrumentation;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.After;
import static org.junit.Assert.*;
import android.view.ViewGroup;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class PlaybackLifecycleTest {
    private Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private Activity activity;
    private void shell(String command) throws Exception {
        try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            byte[] buffer=new byte[1024];while(in.read(buffer)!=-1) { }
        }
    }
    private Activity open() {
        Intent intent = new Intent(getInstrumentation().getTargetContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return getInstrumentation().startActivitySync(intent);
    }
    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> {
            WebView web = (WebView)((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
            web.evaluateJavascript(code, value -> { result.set(value); latch.countDown(); });
        });
        assertTrue("WebView callback", latch.await(10, TimeUnit.SECONDS));
        return result.get();
    }
    private JSONObject state() throws Exception {
        String result = js("AndroidPlayer.getState()");
        return new JSONObject(new JSONArray("[" + result + "]").getString(0));
    }
    private JSONObject awaitTrack(String id) throws Exception {
        long until = SystemClock.uptimeMillis() + 20000;
        JSONObject state = new JSONObject();
        while (SystemClock.uptimeMillis() < until) {
            try { state = state(); if(id.equals(state.optString("id")) && state.optBoolean("playing")) return state; }
            catch (Exception ignored) { }
            SystemClock.sleep(100);
        }
        fail("Expected playing track " + id + "; actual " + state);
        return state;
    }
    private File wav(String name, int seconds) throws Exception {
        int rate = 8000, bytes = rate * seconds * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + bytes).put("WAVEfmt ".getBytes("US-ASCII"));
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate * 2).putShort((short)2).putShort((short)16);
        b.put("data".getBytes("US-ASCII")).putInt(bytes);
        for(int i=0;i<bytes/2;i++) b.putShort((short)(Math.sin(i*2*Math.PI*440/rate)*1000));
        File f = new File(getInstrumentation().getTargetContext().getCacheDir(), name);
        try(FileOutputStream out = new FileOutputStream(f)){out.write(b.array());}
        return f;
    }
    @Test public void testQueueContinuesAfterActivityCloses() throws Exception {
        activity = open();
        JSONArray queue = new JSONArray();
        queue.put(new JSONObject().put("id","first").put("title","First test track").put("url",wav("first.wav",4).getAbsolutePath()));
        queue.put(new JSONObject().put("id","second").put("title","Second test track").put("url",wav("second.wav",40).getAbsolutePath()));
        long until = SystemClock.uptimeMillis()+10000;
        while (!"true".equals(js("typeof AndroidPlayer !== 'undefined'")) && SystemClock.uptimeMillis()<until) SystemClock.sleep(100);
        js("AndroidPlayer.playQueue("+JSONObject.quote(queue.toString())+",0)");
        awaitTrack("first");
        getInstrumentation().runOnMainSync(() -> activity.finish());
        getInstrumentation().waitForIdleSync();
        SystemClock.sleep(6500);
        activity = open();
        JSONObject result = awaitTrack("second");
        assertEquals(1,result.getInt("queueIndex"));
        assertTrue("Playback advanced while activity was destroyed",result.getInt("positionMs")>500);
        js("AndroidPlayer.pause()");
        SystemClock.sleep(500);
        assertFalse(state().getBoolean("playing"));
    }
    @Test public void testStreamingAdvancesWithScreenOffAndSupportsControls() throws Exception {
        final File first=wav("http-first.wav",5),second=wav("http-second.wav",40);
        final java.net.ServerSocket server=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));
        Thread serving=new Thread(() -> {
            while(!server.isClosed()) {
                try(java.net.Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    String request=reader.readLine(),line;
                    while((line=reader.readLine())!=null&&!line.isEmpty()) { }
                    File file=request!=null&&request.contains("/second")?second:first;
                    java.io.OutputStream out=socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: "+file.length()+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    try(java.io.FileInputStream in=new java.io.FileInputStream(file)) {
                        byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                    }
                    out.flush();
                } catch(Exception ignored) { }
            }
        });
        serving.setDaemon(true);serving.start();
        try {
            activity=open();
            JSONArray queue=new JSONArray();
            String root="http://127.0.0.1:"+server.getLocalPort();
            queue.put(new JSONObject().put("id","http-first").put("title","HTTP first").put("url",root+"/first"));
            queue.put(new JSONObject().put("id","http-second").put("title","HTTP second").put("url",root+"/second"));
            long ready=SystemClock.uptimeMillis()+10000;
            while(!"true".equals(js("typeof AndroidPlayer !== 'undefined'"))&&SystemClock.uptimeMillis()<ready)SystemClock.sleep(100);
            js("AndroidPlayer.playQueue("+JSONObject.quote(queue.toString())+",0);AndroidPlayer.pause()");
            SystemClock.sleep(1500);
            assertFalse("Pause during preparation must prevent auto-start",state().optBoolean("playing"));
            js("AndroidPlayer.toggle()");awaitTrack("http-first");
            getInstrumentation().runOnMainSync(() -> activity.finish());
            getInstrumentation().waitForIdleSync();
            shell("input keyevent KEYCODE_HOME");
            shell("input keyevent KEYCODE_SLEEP");
            SystemClock.sleep(7500);
            shell("input keyevent KEYCODE_WAKEUP");
            shell("wm dismiss-keyguard");
            activity=open();
            awaitTrack("http-second");
            js("AndroidPlayer.cycleRepeatMode();AndroidPlayer.next()");
            assertEquals(1,awaitTrack("http-first").getInt("repeatMode"));
            js("AndroidPlayer.previous()");awaitTrack("http-second");
            js("AndroidPlayer.stop()");SystemClock.sleep(400);
            assertEquals(0,state().getInt("queueSize"));
        } finally {
            server.close();
            shell("input keyevent KEYCODE_WAKEUP");
        }
    }
    @After public void tearDown() throws Exception {
        if(activity != null){ try {js("AndroidPlayer.stop()");} catch(Exception ignored){} getInstrumentation().runOnMainSync(() -> activity.finish()); }
    }
}
