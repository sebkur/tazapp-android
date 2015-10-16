package de.thecode.android.tazreader;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.media.RingtoneManager;

import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.squareup.leakcanary.LeakCanary;
import com.squareup.leakcanary.RefWatcher;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import de.thecode.android.tazreader.data.TazSettings;
import de.thecode.android.tazreader.reader.ReaderActivity;
import de.thecode.android.tazreader.service.TazRequestSyncReceiver;
import de.thecode.android.tazreader.utils.Log;
import io.fabric.sdk.android.Fabric;

public class TazReaderApplication extends Application {

    public static final String LOGTAG = "TAZ";

    private OnSharedPreferenceChangeListener listener;

    private static PendingIntent autoDownloadSender;

    private RefWatcher refWatcher;

    public static RefWatcher getRefWatcher(Context context) {
        TazReaderApplication application = (TazReaderApplication) context.getApplicationContext();
        return application.refWatcher;
    }

    @Override
    public void onCreate() {

        super.onCreate();
        CrashlyticsCore core = new CrashlyticsCore.Builder().disabled(BuildConfig.DEBUG).build();
        Fabric.with(this, new Crashlytics.Builder().core(core).build());

        Log.init(this, LOGTAG);
        
        Log.i("TAZReader onCreate()");
        refWatcher = LeakCanary.install(this);

        // Migration von alter Version
        int lastVersionCode = TazSettings.getPrefInt(this, TazSettings.PREFKEY.LASTVERSION, Log.getVersionCode());
        if (lastVersionCode < 16) {
            if (TazSettings.getPrefString(this, TazSettings.PREFKEY.COLSIZE, "0")
                           .equals("4")) TazSettings.setPref(this, TazSettings.PREFKEY.COLSIZE, "3");
        }
        if (lastVersionCode < 32) {
            TazSettings.removePref(this, TazSettings.PREFKEY.FONTSIZE);
            TazSettings.removePref(this, TazSettings.PREFKEY.COLSIZE);
            TazSettings.setPref(this, TazSettings.PREFKEY.PAPERMIGRATEFROM, lastVersionCode);
        }

        // MIGRATION BEENDET, setzten der aktuellen Version
        TazSettings.setPref(this, TazSettings.PREFKEY.LASTVERSION, Log.getVersionCode());

        TazSettings.setPref(this,TazSettings.PREFKEY.ISFOOT,false);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.FONTSIZE, "10");
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.AUTOLOAD, false);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.AUTOLOAD_WIFI, true);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.ISSCROLL, false);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.COLSIZE, "0.5");
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.THEME, ReaderActivity.THEMES.normal.name());
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.FULLSCREEN, false);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.CONTENTVERBOSE, true);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.KEEPSCREEN, false);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.ORIENTATION, "auto");
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.AUTODELETE, false);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.AUTODELETE_DAYS, 14);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.RINGTONE, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.VIBRATE, true);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.ISSOCIAL, false);
        TazSettings.setDefaultPref(this, TazSettings.PREFKEY.PAGEINDEXBUTTON, false);

        listener = new SharedPreferences.OnSharedPreferenceChangeListener() {

            public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                if (key.equals(TazSettings.PREFKEY.AUTOLOAD)) {
                    Log.d("AutoLoad Pref geändert");
                    checkAutoDownload(TazReaderApplication.this);
                }
            }
        };
        TazSettings.getSharedPreferences(this)
                   .registerOnSharedPreferenceChangeListener(listener);

        autoDownloadSender = PendingIntent.getBroadcast(this, 0, new Intent(this, TazRequestSyncReceiver.class), 0);



        //TazReaderHTTPD.startInstance();

        unregisterAutoDownload(this);
        checkAutoDownload(this);

    }

    @Override
    public void onTerminate() {
        //TazReaderHTTPD.stopInstance();
        // db.closeDB();
        TazSettings.getSharedPreferences(this)
                   .unregisterOnSharedPreferenceChangeListener(listener);
        super.onTerminate();
    }

    public static void checkAutoDownload(Context context) {
        if (TazSettings.getPrefBoolean(context, TazSettings.PREFKEY.AUTOLOAD, false)) registerAutoDownload(context, false);
        else unregisterAutoDownload(context);
    }


    public static void registerAutoDownload(Context context, boolean notToday) {
        Log.d("registerAutoDownload");
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, getNextAutodownloadCheckTime(notToday), autoDownloadSender);
    }

    private static void unregisterAutoDownload(Context context) {
        Log.d("unregisterAutoDownload");
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        am.cancel(autoDownloadSender);
    }

    static final int HOUR_OF_DAY = Calendar.HOUR_OF_DAY;
    static final int MINUTE = Calendar.MINUTE;
    static final int DAY_OF_WEEK = Calendar.DAY_OF_WEEK;

    private static long getNextAutodownloadCheckTime(boolean notToday) {

        int checkFromHour1 = 19;
        int checkEveryMinutes1 = 15;
        int checkFromHour2 = 21;
        int checkEveryMinutes2 = 30;

        ArrayList<Integer> minutes1 = new ArrayList<Integer>();
        ArrayList<Integer> minutes2 = new ArrayList<Integer>();
        for (int i = 0; i <= 59; i = i + checkEveryMinutes1) {
            minutes1.add(i);
        }
        for (int i = 0; i <= 59; i = i + checkEveryMinutes2) {
            minutes2.add(i);
        }

        TimeZone berlinTimeZone = TimeZone.getTimeZone("Europe/Berlin");
        GregorianCalendar cal;
        try {
            cal = new GregorianCalendar(berlinTimeZone);
        } catch (Exception e) {
            cal = new GregorianCalendar();
        }

        cal.setTimeInMillis(System.currentTimeMillis());// set the current time and date for this calendar
        Log.d("aktuelle Zeit:" + cal.getTime()
                                    .toString());
        Log.d("aktuelle Zeit:" + cal.getTimeInMillis());
        if (notToday) {
            Log.d("nicht heute!");
            cal.add(DAY_OF_WEEK, 1);
            cal.set(HOUR_OF_DAY, 0);
            cal.set(MINUTE, 0);
        }
        while (cal.get(DAY_OF_WEEK) == GregorianCalendar.SATURDAY) {
            cal.add(MINUTE, 1);
        }
        cal.add(MINUTE, 1);
        if (cal.get(HOUR_OF_DAY) < checkFromHour1) {
            cal.set(HOUR_OF_DAY, checkFromHour1);
            cal.set(MINUTE, 0);
        } else if (cal.get(HOUR_OF_DAY) < checkFromHour2) {
            while (!minutes1.contains(cal.get(MINUTE))) {
                cal.add(MINUTE, 1);
            }
        } else {
            while (!minutes2.contains(cal.get(MINUTE))) {
                cal.add(MINUTE, 1);
            }
        }
        Log.d("nächste Zeit:" + cal.getTime()
                                   .toString());
        Log.d("nächste Zeit:" + cal.getTimeInMillis());
        return cal.getTimeInMillis();
    }


}
