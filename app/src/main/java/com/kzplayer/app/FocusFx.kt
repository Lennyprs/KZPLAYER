package com.kzplayer.app

import android.view.View

// v391 : effet de curseur (telecommande) net sur les boutons plats : la vue ciblee
// grandit legerement et passe au premier plan, en plus du fond accent du selecteur.
object FocusFx {
    fun apply(vararg views: View?) = applyScaled(1.07f, 16f, *views)

    // Écran d'activation : focus visible mais contenu dans les cartes et le panneau.
    fun applySubtle(vararg views: View?) = applyScaled(1.015f, 6f, *views)

    private fun applyScaled(focusedScale: Float, focusedElevation: Float, vararg views: View?) {
        for (v in views) {
            if (v == null) continue
            v.isFocusable = true
            v.isClickable = true
            v.setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) focusedScale else 1f)
                    .scaleY(if (hasFocus) focusedScale else 1f)
                    .setDuration(80).start()
                view.translationZ = if (hasFocus) focusedElevation else 0f
            }
        }
    }
}
