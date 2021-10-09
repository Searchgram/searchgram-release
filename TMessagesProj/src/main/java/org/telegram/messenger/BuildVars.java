/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2020.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

public class BuildVars {

    public static boolean DEBUG_VERSION         = false;
    public static boolean DEBUG_PRIVATE_VERSION = false;
    public static boolean LOGS_ENABLED = false;
    public static boolean USE_CLOUD_STRINGS = false;
    public static boolean CHECK_UPDATES = false;
    public static boolean NO_SCOPED_STORAGE = true/* || Build.VERSION.SDK_INT <= 28*/;
    /*
    public static int BUILD_VERSION = 2431;
    public static String BUILD_VERSION_STRING = "8.1.1";
    public static int APP_ID = 4;
    public static String APP_HASH = "014b35b6184100b085b0d0572f9b5103";
    public static String APPCENTER_HASH = "a5b5c4f5-51da-dedc-9918-d9766a22ca7c";
    public static String APPCENTER_HASH_DEBUG = "f9726602-67c9-48d2-b5d0-4761f1c1a8f3";
    //
    public static String SMS_HASH = isStandaloneApp() ? "w0lkcmTZkKh" : (DEBUG_VERSION ? "O2P2z+/jBpJ" : "oLeq9AcOZkT");
    public static String PLAYSTORE_APP_URL = "https://play.google.com/store/apps/details?id=org.telegram.messenger";
    */

    public static int     BUILD_VERSION         = BuildConfig.OFFICIAL_VERSION_CODE;
    public static String  BUILD_VERSION_STRING  = BuildConfig.OFFICIAL_VERSION_NAME;
    public static int     APP_ID                = BuildConfig.APP_ID;
    public static String  APP_HASH              = BuildConfig.APP_HASH;
    
    public static String  SMS_HASH              = DEBUG_VERSION ? "O2P2z+/jBpJ" : "oLeq9AcOZkT";
    public static String  PLAYSTORE_APP_URL     = "https://play.google.com/store/apps/details?id=app.searchgram.messenger";

    static {
        if (ApplicationLoader.applicationContext != null) {
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
            LOGS_ENABLED = sharedPreferences.getBoolean("logsEnabled", LOGS_ENABLED);
        }
    }

    public static void logVars() {
        if (LOGS_ENABLED) {
            FileLog.d(String.format("DEBUG_VERSION %s, DEBUG_PRIVATE_VERSION %s, LOGS_ENABLED %s, USE_CLOUD_STRINGS %s, CHECK_UPDATES %s", DEBUG_VERSION, DEBUG_PRIVATE_VERSION, LOGS_ENABLED, USE_CLOUD_STRINGS, CHECK_UPDATES));
            FileLog.d(String.format("BUILD_VERSION %s, BUILD_VERSION_STRING %s, APP_ID %s", BUILD_VERSION, BUILD_VERSION_STRING, APP_ID));
            FileLog.d(String.format("SMS_HASH %s, PLAYSTORE_APP_URL %s", SMS_HASH, PLAYSTORE_APP_URL));
        }
    }

    private static Boolean standaloneApp;
    public static boolean isStandaloneApp() {
        if (standaloneApp == null) {
            standaloneApp = ApplicationLoader.applicationContext != null && "org.telegram.messenger.web".equals(ApplicationLoader.applicationContext.getPackageName());
        }
        return standaloneApp;
    }

    private static Boolean betaApp;
    public static boolean isBetaApp() {
        if (betaApp == null) {
            betaApp = ApplicationLoader.applicationContext != null && "org.telegram.messenger.beta".equals(ApplicationLoader.applicationContext.getPackageName());
        }
        return betaApp;
    }
}
