package com.kzplayer.app

import android.content.Context
import android.content.Intent

object LiveSearch {
    fun open(context:Context) {
        context.startActivity(Intent(context,BrowseActivity::class.java)
            .putExtra("kind","live").putExtra("liveGlobalSearch",true))
    }
}
