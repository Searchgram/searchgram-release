package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import org.telegram.messenger.R;

public class SG {
	private SG() {
	}

	public static void reloadWithContext(Context ctx) {
		drawable.reloadWithContext(ctx);
	}

	public static final class drawable {
		private drawable() {
		}

		public static Drawable result_cell_name_icon;

		private static void reloadWithContext(Context ctx) {
			result_cell_name_icon = ctx.getDrawable(R.drawable.sg_result_cell_name_icon);
		}
	}

	public static final class string {
		public static final String raw_searchgram = "Searchgram";
	}
}
