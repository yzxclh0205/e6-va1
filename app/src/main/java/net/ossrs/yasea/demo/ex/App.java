package net.ossrs.yasea.demo.ex;

import android.app.Application;

import com.tencent.bugly.crashreport.CrashReport;

/**
 * Created by lh on 2018/11/22.
 */

public class App extends Application{

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.getInstance().init(getApplicationContext());
        CrashReport.initCrashReport(getApplicationContext(), "1c6f1c7663", true);
    }
}
