package me.venkatesh.saveobsidian;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tiles: one swipe and tap from anywhere. The voice tile shows, and stops, a recording in progress. */
public abstract class QuickTile extends TileService {
    abstract boolean voice();

    @Override public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean recording = voice() && RecordService.active;
        tile.setLabel(voice() ? (recording ? "Recording" : "Voice note") : "Quick note");
        tile.setIcon(Icon.createWithResource(this, voice() ? R.drawable.ic_tile_mic : R.drawable.ic_tile_pen));
        tile.setState(recording ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @SuppressWarnings("deprecation")
    @Override public void onClick() {
        if (voice() && RecordService.active) { startService(new Intent(this, RecordService.class).setAction(RecordService.STOP)); return; }
        Intent open = new Intent(this, QuickActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (voice()) open.setAction(QuickActivity.VOICE);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, voice() ? 2 : 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else startActivityAndCollapse(open);
    }

    public static class Note extends QuickTile { boolean voice() { return false; } }
    public static class Voice extends QuickTile { boolean voice() { return true; } }
}
