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
    public static boolean LOGS_ENABLED          = false;
    public static boolean USE_CLOUD_STRINGS     = false;
    public static boolean CHECK_UPDATES         = false;
    public static int     BUILD_VERSION         = BuildConfig.OFFICIAL_VERSION_CODE;
    public static String  BUILD_VERSION_STRING  = BuildConfig.OFFICIAL_VERSION_NAME;
    public static int     APP_ID                = BuildConfig.APP_ID;
    public static String  APP_HASH              = BuildConfig.APP_HASH;
    //
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
}
