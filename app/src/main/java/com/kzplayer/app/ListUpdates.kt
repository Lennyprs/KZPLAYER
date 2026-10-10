package com.kzplayer.app

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

// v418 : garder les memes vues lors des arrivees de resultats serveur.
// Aucun detach de l'adaptateur ni notifyDataSetChanged pour ces mises a jour.
object ListUpdates {
    fun <T> submit(adapter: RecyclerView.Adapter<*>, data: MutableList<T>, incoming: List<T>, key: (T) -> String) {
        val next = incoming.toList()
        if (data == next) return
        val old = data.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(a: Int, b: Int) = key(old[a]) == key(next[b])
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b]
        })
        data.clear()
        data.addAll(next)
        diff.dispatchUpdatesTo(adapter)
    }

    // Le titre d'un resultat fusionne reste le meme quand un deuxieme serveur
    // ajoute son nom, une affiche ou un lien : cela ne doit pas creer une autre tuile.
    fun itemKey(item: Item): String = item.kind + "|" + item.name + "|" + item.season
}
