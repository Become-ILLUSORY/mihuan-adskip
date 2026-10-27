package com.becomeillusory.adskip;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** Minimal info screen; the module's real work is done by HookEntry. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(0f, 1.15f);
        tv.setText(
            "猛鬼宿舍 · 广告跳过模块\n\n"
          + "作用：在 com.mgss.mihuan 中拦截「看广告领奖励」，不播放激励视频，"
          + "直接向游戏下发奖励。\n\n"
          + "使用方法：\n"
          + "1. 安装本模块后打开 LSPosed 管理器\n"
          + "2. 在「模块」中启用『猛鬼宿舍 广告跳过』\n"
          + "3. 作用域勾选『猛鬼宿舍 / com.mgss.mihuan』\n"
          + "4. 强制停止游戏进程，再重新打开游戏\n"
          + "5. 游戏内点击「看广告」按钮，即可直接到账\n\n"
          + "原理：\n"
          + "游戏是 LayaAir + SecShell 加固，奖励逻辑在 JS。各渠道都会调用\n"
          + "  PlatformClass.createClass(\"demo.AD\")\n"
          + "      .callWithBack(cb, \"ShowRewardVideo\")\n"
          + "本模块 hook demo.AD.ShowRewardVideo，不播放广告，并通过\n"
          + "layaair.game.browser.ConchJNI 直接把成功回调 true 交给游戏。\n\n"
          + "排查：\n"
          + "adb logcat -s MihuanAdSkip\n"
          + "（若无效，请把日志发给作者）"
        );

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }
}
