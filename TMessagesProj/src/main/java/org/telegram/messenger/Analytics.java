package org.telegram.messenger;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.analytics.FirebaseAnalytics.Event;
import com.google.firebase.analytics.FirebaseAnalytics.Param;
import org.telegram.ui.ActionBar.BaseFragment;

public final class Analytics {
	private Analytics() {
	}

	public static FirebaseAnalytics firebaseAnalytics;

	public static void initialize(Context ctx) {
		if (firebaseAnalytics == null)
			firebaseAnalytics = FirebaseAnalytics.getInstance(ctx);
	}

	public static final String SEARCH_SCENE_GLOBAL = "G-";
	public static final String SEARCH_SCENE_CHAT = "C-";
	public static final String SEARCH_SCENE_SECURE = "S-";

	public static void searchEvent(String scene, String query) {
		if (firebaseAnalytics == null || TextUtils.isEmpty(query))
			return;
		Bundle bundle = new Bundle();
		bundle.putString(Param.SEARCH_TERM, scene + query.length());
		firebaseAnalytics.logEvent(Event.SEARCH, bundle);
	}

	public static void screenView(BaseFragment fragment) {
		if (firebaseAnalytics == null || fragment == null)
			return;
		Bundle bundle = new Bundle();
		bundle.putString(Param.SCREEN_NAME, fragment.getClass().getSimpleName());
		firebaseAnalytics.logEvent(Event.SCREEN_VIEW, bundle);
	}

	public static void ftsRebuildSize(int currentAccount, int count) {
		if (firebaseAnalytics == null)
			return;
		Bundle bundle = new Bundle();
		bundle.putInt("current_account", currentAccount);
		bundle.putInt("count", count);
		firebaseAnalytics.logEvent("fts_rebuild", bundle);
	}

	public static void ftsSize(int currentAccount, int count) {
		if (firebaseAnalytics == null)
			return;
		Bundle bundle = new Bundle();
		bundle.putInt("current_account", currentAccount);
		bundle.putInt("count", count);
		firebaseAnalytics.logEvent("fts_size", bundle);
	}

	public static void ftsGlobalTime(long ms) {
		if (firebaseAnalytics == null)
			return;
		Bundle bundle = new Bundle();
		bundle.putLong("time", ms);
		firebaseAnalytics.logEvent("fts_global_time", bundle);
	}

	public static void ftsChatTime(long ms) {
		if (firebaseAnalytics == null)
			return;
		Bundle bundle = new Bundle();
		bundle.putLong("time", ms);
		firebaseAnalytics.logEvent("fts_chat_time", bundle);
	}
}
