package app.starrnote.android;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.util.List;

public final class NotebookWidget extends AppWidgetProvider {
    static final String NEW = "app.starrnote.android.widget.NEW";
    static final String OPEN = "app.starrnote.android.widget.OPEN";
    static final String NOTE = "notebookId";
    private static final String STORE = "starrnote_widget";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, views(context));
    }

    static void publish(Context context, List<Notebook> notes, Notebook lastOpened) {
        SharedPreferences prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        String recentId = lastOpened == null ? prefs.getString("recent", "") : lastOpened.id;
        Notebook recent = null, pinned = null;
        int notebooks = 0, pages = 0;
        for (Notebook note : notes) {
            if (note.trashed) continue;
            notebooks++;
            pages += note.pages.size();
            if (note.deviceLock) continue;
            if (note.id.equals(recentId)) recent = note;
            if (note.favorite && (pinned == null || note.updated > pinned.updated)) pinned = note;
        }
        String id = recent == null ? "" : recent.id, title = recent == null ? "" : recent.name;
        String pin = pinned == null ? "" : pinned.id, pinTitle = pinned == null ? "" : pinned.name;
        String stats = notebooks + (notebooks == 1 ? " notebook" : " notebooks") + " · "
                + pages + (pages == 1 ? " page" : " pages");
        if (id.equals(prefs.getString("recent", "")) && title.equals(prefs.getString("recentTitle", ""))
                && pin.equals(prefs.getString("pinned", "")) && pinTitle.equals(prefs.getString("pinnedTitle", ""))
                && stats.equals(prefs.getString("stats", ""))) return;
        prefs.edit().putString("recent", id).putString("recentTitle", title)
                .putString("pinned", pin).putString("pinnedTitle", pinTitle).putString("stats", stats).apply();
        AppWidgetManager.getInstance(context).updateAppWidget(new ComponentName(context, NotebookWidget.class),
                views(context));
    }

    private static RemoteViews views(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.notebook_widget);
        views.setTextViewText(R.id.widget_stats, prefs.getString("stats", "Open StarrNote to get started"));
        views.setOnClickPendingIntent(R.id.widget_stats, target(context, 1, OPEN, ""));
        views.setOnClickPendingIntent(R.id.widget_new, target(context, 2, NEW, ""));
        bind(context, views, prefs, "recent", "Recent", R.id.widget_recent, 3);
        bind(context, views, prefs, "pinned", "Pinned", R.id.widget_pinned, 4);
        return views;
    }

    private static void bind(Context context, RemoteViews widget, SharedPreferences prefs, String key, String label,
                             int viewId, int request) {
        String title = prefs.getString(key + "Title", "");
        widget.setTextViewText(viewId, title.isEmpty() ? label : label + " · " + title);
        widget.setOnClickPendingIntent(viewId, target(context, request, OPEN, prefs.getString(key, "")));
    }

    private static PendingIntent target(Context context, int request, String action, String note) {
        Intent intent = new Intent(context, MainActivity.class).setAction(action).putExtra(NOTE, note)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
