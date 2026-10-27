package com.becomeillusory.adskip;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * LSPosed module for 猛鬼宿舍 (com.mgss.mihuan).
 *
 * The game is a LayaAir (Conch) native app protected by SecShell; the real game
 * logic is JavaScript.  Every channel implementation calls:
 *
 *     PlatformClass.createClass("demo.AD").callWithBack(cb, "ShowRewardVideo")
 *
 * which lands on the Java class demo.AD -> method ShowRewardVideo(String), and
 * grants the reward when the JS callback receives `true`.
 *
 * We hook ShowRewardVideo, prevent the rewarded video from being played, and
 * then deliver `true` straight into the game's registered JS callback through
 * the LayaAir Java-&gt;JS bridge (layaair.game.browser.ConchJNI).
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "MihuanAdSkip";
    private static final String PKG = "com.mgss.mihuan";
    private static final String AD_CLASS = "demo.AD";
    private static final String CONCH = "layaair.game.browser.ConchJNI";

    private volatile ClassLoader sCl;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) return;
        sCl = lpparam.classLoader;
        XposedBridge.log(TAG + ": package loaded, scheduling hook");
        Thread t = new Thread(new Runnable() {
            @Override public void run() { waitAndHook(); }
        }, "adskip-init");
        t.setDaemon(true);
        t.start();
    }

    /** demo.AD is loaded lazily (after SecShell decrypts the dex), so poll for it. */
    private void waitAndHook() {
        long deadline = System.currentTimeMillis() + 300_000L; // 5 min
        while (System.currentTimeMillis() < deadline) {
            try {
                Class<?> ad = Class.forName(AD_CLASS, false, sCl);
                if (ad != null) {
                    hookAD(ad);
                    return;
                }
            } catch (Throwable ignore) {
                // not loaded yet
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
        }
        XposedBridge.log(TAG + ": " + AD_CLASS + " not found before timeout");
    }

    private void hookAD(Class<?> ad) {
        boolean hookedAny = false;
        for (final Method m : ad.getDeclaredMethods()) {
            final String n = m.getName();
            String ln = n.toLowerCase(Locale.ROOT);
            if (ln.contains("reward") || n.equals("ShowRewardVideo")) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);      // skip the ad entirely
                            deliverReward(n);
                        }
                    });
                    hookedAny = true;
                    XposedBridge.log(TAG + ": hooked " + AD_CLASS + "." + n
                            + "(" + m.getParameterTypes().length + " params)");
                } catch (Throwable e) {
                    XposedBridge.log(TAG + ": hook failed " + n + " : " + e);
                }
            }
        }

        if (!hookedAny) {
            // Fall back to the exact JS-declared signature.
            try {
                XposedHelpers.findAndHookMethod(AD_CLASS, sCl, "ShowRewardVideo",
                        String.class, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(null);
                                deliverReward("ShowRewardVideo");
                            }
                        });
                hookedAny = true;
                XposedBridge.log(TAG + ": hooked fallback " + AD_CLASS + ".ShowRewardVideo(String)");
            } catch (Throwable e) {
                XposedBridge.log(TAG + ": fallback hook failed : " + e);
            }
        }
        XposedBridge.log(TAG + ": hookAD finished, any=" + hookedAny);
    }

    /**
     * Run JS inside the game that resolves the callback the game registered for
     * `methodName` on the demo.AD PlatformClass instance and invokes it with true.
     * Falls back to conch.platCallBack if the internal structures are not found.
     */
    private void deliverReward(String methodName) {
        final String name = (methodName == null || methodName.isEmpty())
                ? "ShowRewardVideo" : methodName;

        String js =
            "(function(){try{"
          + "var n='" + name.replace("'", "") + "';"
          + "var pc=(typeof PlatformClass!=='undefined')?PlatformClass:((typeof window!=='undefined')?window.PlatformClass:null);"
          + "if(pc&&pc.clsMap){var inst=pc.clsMap['demo.AD'];if(inst&&inst.callBackList&&inst.callBackList[n]){var cb=inst.callBackList[n];inst.callBackList[n]=null;cb(true);return 'CB_OK';}}"
          + "if(typeof conch!=='undefined'&&conch&&conch.platCallBack){conch.platCallBack({objId:-1,cName:'demo.AD',mName:n,v:true});return 'PLAT_OK';}"
          + "return 'NONE';}catch(e){return 'ERR:'+e;}})()";

        // Strategy 1: ConchJNI.RunJS(js)  — simplest, evaluates arbitrary JS.
        try {
            Class<?> cj = Class.forName(CONCH, true, sCl);
            Method runjs = findSingleStringMethod(cj, "RunJS");
            if (runjs != null) {
                runjs.setAccessible(true);
                Object ret = invokeMaybeStatic(runjs, cj, js);
                XposedBridge.log(TAG + ": RunJS ret=" + ret);
                return;
            }
            XposedBridge.log(TAG + ": ConchJNI.RunJS(String) not found");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": RunJS failed : " + e);
        }

        // Strategy 2: ConchJNI.callConchJSFunction("platCallBack", json, "")
        try {
            Class<?> cj = Class.forName(CONCH, true, sCl);
            String payload = "{\"objId\":-1,\"cName\":\"demo.AD\",\"mName\":\""
                    + name + "\",\"v\":true}";
            Method m3 = null;
            for (Method mm : cj.getDeclaredMethods()) {
                if (mm.getName().equals("callConchJSFunction")
                        && mm.getParameterTypes().length == 3) { m3 = mm; break; }
            }
            if (m3 != null) {
                m3.setAccessible(true);
                Object ret = invokeMaybeStatic(m3, cj, "platCallBack", payload, "");
                XposedBridge.log(TAG + ": callConchJSFunction ret=" + ret);
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": callConchJSFunction failed : " + e);
        }
    }

    private static Method findSingleStringMethod(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)
                    && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == String.class) {
                return m;
            }
        }
        return null;
    }

    private static Object invokeMaybeStatic(Method m, Class<?> owner, Object... args) throws Exception {
        if (Modifier.isStatic(m.getModifiers())) {
            return m.invoke(null, args);
        }
        // Should not happen, but try an instance if needed.
        return m.invoke(owner.newInstance(), args);
    }
}
