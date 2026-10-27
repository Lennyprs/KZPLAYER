package com.kzplayer.app

import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

open class NflxLiveActivity : NflxCatalogActivity() {
    override val kind = "live"
    override val screenTitle = "TV en direct"
    override val landscape = true
    override fun onCardClick(item: Item) {
        LiveHistory.activateOwner(item)
        LiveHistory.touch(this,item)
        // v353 : on zappe dans la rangee (categorie) d ou vient la chaine cliquee.
        val rowChans = lastRowItems.filter { it.kind == "live" || it.kind == "channel" }
        val chans = if (rowChans.size > 1) rowChans else allLoadedItems().filter { it.kind == "live" || it.kind == "channel" }
        Session.liveChannels = chans
        ZapList.set(chans, item)
        val pl = Session.current
        val direct = item.directUrl
        val cmd = item.cmd
        lifecycleScope.launch {
            val url = when {
                !direct.isNullOrBlank() -> direct
                pl != null && !cmd.isNullOrBlank() -> Api.stalkerLink(pl, cmd, "live")
                else -> null
            }
            if (url.isNullOrBlank()) {
                Toast.makeText(this@NflxLiveActivity, "Lecture indisponible.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                Intent(this@NflxLiveActivity, PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("title", item.name)
                    .putExtra("logo", item.logo)
                    .putExtra("historyKind", "live")
                    .putExtra("mode", "live")
                    .putExtra("historySourceStreamId", item.streamId ?: "")
                    .putExtra("historySourceCmd", item.cmd ?: "")
                    .putExtra("zapIndex", Session.zapIndex)
            )
        }
    }
}
