package com.johndsdev.androidnav;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
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

public class PlaybackLifecycleTest extends InstrumentationTestCase {
    private Activity activity;
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
    public void testQueueContinuesAfterActivityCloses() throws Exception {
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
    @Override protected void tearDown() throws Exception {
        if(activity != null){ try {js("AndroidPlayer.stop()");} catch(Exception ignored){} getInstrumentation().runOnMainSync(() -> activity.finish()); }
        super.tearDown();
    }
}
