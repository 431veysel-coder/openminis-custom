package com.openminis.app.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * [MINIS-OZEL] Quick Settings tile — one tap from the notification shade into
 * a new chat.
 *
 * ## Why a tile and not just the launcher icon
 *
 * The launcher icon costs two gestures (swipe up, then find it) and the app is
 * usually opened for one short question. The shade is reachable from any screen
 * with one swipe, and the tile is a single target — so "ask Minis something"
 * goes from ~3 s to ~1 s.
 *
 * ## What it does
 *
 * Fires the same deep link the app-icon long-press shortcut uses
 * (`minis://action/new_chat`), which MainActivity already routes. That keeps
 * this file free of navigation logic: the tile is a button, not a second entry
 * point into the app's state machine.
 *
 * ## Not a toggle
 *
 * It reports STATE_INACTIVE always. Quick Settings tiles that pretend to have
 * an on/off state ("is a chat open?") go stale the moment the user navigates
 * in the app; an action button is honest about what it is.
 */
@RequiresApi(Build.VERSION_CODES.N)
class MinisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // [MINIS-OZEL] Settings › Minis Özel can switch the tile off. Android
        // gives an app no way to remove its own tile from the panel, so the
        // honest state is STATE_UNAVAILABLE: the tile stays where the user put
        // it, greyed out, and does nothing — instead of launching a chat from
        // a feature they switched off.
        val enabled = com.openminis.app.data.OzelFeatures.isEnabled(
            this,
            com.openminis.app.data.OzelFeatures.Feature.QS_TILE,
        )
        qsTile?.apply {
            if (!enabled) {
                state = Tile.STATE_UNAVAILABLE
                updateTile()
                return@apply
            }
            state = Tile.STATE_INACTIVE
            label = getString(com.openminis.app.R.string.qs_tile_label)
            // Android 10+ lets a tile carry a subtitle; harmless on older APIs.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(com.openminis.app.R.string.qs_tile_subtitle)
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (!com.openminis.app.data.OzelFeatures.isEnabled(
                this,
                com.openminis.app.data.OzelFeatures.Feature.QS_TILE,
            )
        ) {
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DEEP_LINK))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 requires the PendingIntent overload; the Intent one
            // throws. Both collapse the shade and launch the activity.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        /** Same target as the app-icon long-press "New chat" shortcut. */
        const val DEEP_LINK = "minis://action/new_chat"
    }
}
