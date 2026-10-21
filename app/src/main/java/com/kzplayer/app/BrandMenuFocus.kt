package com.kzplayer.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import org.json.JSONObject
import java.util.WeakHashMap

/** Decoration de focus des menus seulement. Aucune touche interceptee, aucun lecteur modifie. */
object BrandMenuFocus {
    private val holders = WeakHashMap<Activity, Holder>()
    fun attach(a: Activity) {
        if (BuildConfig.BRAND_ID.isBlank() || a is PlayerActivity || a is LivePreviewActivity || a is TrailerActivity || holders.containsKey(a)) return
        try {
            val id = a.resources.getIdentifier("brand_ui", "raw", a.packageName)
            if (id == 0) return
            val data = a.resources.openRawResource(id).bufferedReader().use { JSONObject(it.readText()) }
            val focus = data.getJSONObject("focus")
            val color = Color.parseColor(focus.getString("color"))
            val style = focus.getString("style")
            if (style !in listOf("outline", "halo", "zoom")) return
            val holder = Holder(a.window.decorView, color, style)
            holders[a] = holder
            holder.start()
        } catch (_: Exception) { /* Le focus Android natif reste disponible. */ }
    }
    fun detach(a: Activity) { holders.remove(a)?.stop() }

    private class Holder(private val decor: View, private val color: Int, private val style: String) {
        private var target: View? = null
        private var originalX = 1f
        private var originalY = 1f
        private val density = decor.resources.displayMetrics.density
        private val ring = object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
            override fun draw(canvas: Canvas) {
                val v = target ?: return
                if (!v.isShown || !v.hasFocus()) return
                val origin = IntArray(2); val point = IntArray(2)
                decor.getLocationOnScreen(origin); v.getLocationOnScreen(point)
                val inset = 2f * density
                val box = RectF((point[0]-origin[0]).toFloat()+inset, (point[1]-origin[1]).toFloat()+inset,
                    point[0]-origin[0]+v.width*v.scaleX-inset, point[1]-origin[1]+v.height*v.scaleY-inset)
                if (box.width() <= 0 || box.height() <= 0) return
                val radius = if (v.id == decor.resources.getIdentifier("playlistBtn", "id", decor.context.packageName)) box.height()/2f else if (v.tag == "brand:home-card") 22f*density else 12f*density
                fun stroke(width: Float, alpha: Int) { paint.color=color; paint.alpha=alpha; paint.strokeWidth=width*density; canvas.drawRoundRect(box,radius,radius,paint) }
                if (style == "halo") { stroke(12f,24); stroke(8f,48) }
                stroke(4f,255)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, next -> focus(next) }
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { ring.setBounds(0,0,decor.width,decor.height); ring.invalidateSelf() }
        private val scrollListener = ViewTreeObserver.OnScrollChangedListener { ring.invalidateSelf() }
        fun start() {
            decor.overlay.add(ring)
            decor.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
            decor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            decor.viewTreeObserver.addOnScrollChangedListener(scrollListener)
            ring.setBounds(0,0,decor.width,decor.height)
            focus(decor.findFocus())
        }
        private fun restore() { target?.let { it.scaleX=originalX; it.scaleY=originalY } }
        private fun focus(next: View?) {
            if (target === next) { ring.invalidateSelf(); return }
            restore(); target=next
            next?.let { originalX=it.scaleX; originalY=it.scaleY; if (style=="zoom") { it.scaleX=originalX*1.025f; it.scaleY=originalY*1.025f } }
            ring.invalidateSelf()
        }
        fun stop() {
            restore(); target=null
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
                decor.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                decor.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
            }
            decor.overlay.remove(ring)
        }
    }
}
