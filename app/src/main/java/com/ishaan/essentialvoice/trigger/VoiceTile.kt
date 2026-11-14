package com.ishaan.essentialvoice.trigger

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ishaan.essentialvoice.voice.Bar
import com.ishaan.essentialvoice.voice.Dictation

class VoiceTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        Dictation.watch("VoiceTile", onActivity = { _, _ -> paint() })
        paint()
    }

    override fun onStopListening() {
        Dictation.unwatch("VoiceTile")
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (Dictation.isReady) {
            if (!Dictation.isBusy) Bar.claim()
            Dictation.toggle()
        }
        paint()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val busy = Dictation.isBusy
        tile.state = if (busy) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "GlyphVoice"
        tile.subtitle = if (busy) "Listening..." else "Tap to dictate"
        tile.updateTile()
    }
}
