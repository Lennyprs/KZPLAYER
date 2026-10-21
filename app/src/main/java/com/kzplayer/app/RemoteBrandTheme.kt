package com.kzplayer.app

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/** Images et couleurs seulement. Aucune commande distante ni changement du lecteur. */
object RemoteBrandTheme {
 private data class Skin(val revision:Long,val enabled:Boolean,val name:String,val color:Int,val accent:Int,val shade:Float,val logo:Bitmap?,val icon:Bitmap?,val background:Bitmap?,val menuAccent:Boolean=false)
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private val mutex=Mutex()
 private val handler=Handler(Looper.getMainLooper())
 private val client=OkHttpClient.Builder().connectTimeout(6,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
 private val backgrounds=WeakHashMap<View,Drawable?>()
 private val images=WeakHashMap<ImageView,Drawable?>()
 private val cardBackgrounds=WeakHashMap<View,Drawable?>()
 private val iconTints=WeakHashMap<ImageView,ColorStateList?>()
 private val texts=WeakHashMap<TextView,Pair<CharSequence,Int>>()
 @Volatile private var skin:Skin?=null
 private var loaded=false
 private var lastCheck=-120000L
 private var active=WeakReference<BaseActivity>(null)
 private val tick=object:Runnable{override fun run(){val a=active.get()?:return;if(!eligible(a))return;paint(a);refresh(a);handler.postDelayed(this,120000)}}
 private fun eligible(a:Activity)=BuildConfig.BRAND_ID.isNotBlank()&&a !is PlayerActivity&&a !is LivePreviewActivity&&a !is TrailerActivity
 fun attach(a:BaseActivity){if(eligible(a))paint(a)}
 fun resume(a:BaseActivity){if(!eligible(a))return;active=WeakReference(a);handler.removeCallbacks(tick);paint(a);refresh(a);handler.postDelayed(tick,120000)}
 fun pause(a:BaseActivity){if(active.get()===a){active.clear();handler.removeCallbacks(tick)}}
 private fun cache(a:Activity)=AtomicFile(File(a.filesDir,"brand-theme-${BuildConfig.BRAND_ID}.json"))
 private fun refresh(a:BaseActivity){
  
  scope.launch{
   mutex.withLock{
    if(!loaded){loaded=true;try{val text=cache(a).openRead().bufferedReader().use{it.readText()};if(text.length<=3200000)skin=parse(JSONObject(text))}catch(_:Exception){}catch(_:OutOfMemoryError){};notifyUi()}
    val now=android.os.SystemClock.elapsedRealtime();if(now-lastCheck<120000)return@withLock;lastCheck=now
    try{
     val form=FormBody.Builder().add("action","brandThemeGet").add("brand_id",BuildConfig.BRAND_ID).add("revision",(skin?.revision?:-1L).toString()).build()
     val text=client.newCall(Request.Builder().url(Config.LOGIN_URL).post(form).build()).execute().use{response->check(response.isSuccessful);val body=response.body?:error("Empty theme response");check(body.contentLength()<=3200000);val bytes=body.byteStream().use{input->val out=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=3200000);out.write(buffer,0,n)};out.toByteArray()};String(bytes,Charsets.UTF_8)}
     val json=JSONObject(text);check(json.optBoolean("ok")&&json.optString("brand_id")==BuildConfig.BRAND_ID)
     if(!json.optBoolean("unchanged")){
      val next=parse(json);check(next.revision>=(skin?.revision?:0L));val atomic=cache(a);val output=atomic.startWrite();try{output.write(text.toByteArray(Charsets.UTF_8));atomic.finishWrite(output)}catch(e:Exception){atomic.failWrite(output);throw e};skin=next;notifyUi()
     }
    }catch(_:Exception){/* Offline ou réponse invalide : conserver le dernier habillage valide. */}catch(_:OutOfMemoryError){/* Ne pas interrompre l'app sur un appareil à faible mémoire. */}
   }
  }
 }
 private fun parse(json:JSONObject):Skin{
  check(json.optBoolean("ok")&&json.optString("brand_id")==BuildConfig.BRAND_ID)
  val revision=json.optLong("revision");check(revision>=0)
  val c=json.optJSONObject("config")?:return Skin(revision,false,"",Color.BLACK,Color.WHITE,0f,null,null,null)
  check(c.optInt("schema")==1)
  val name=c.optString("display_name");check(name.length<=40)
  val shade=c.optDouble("shade",0.5).toFloat();check(shade.isFinite()&&shade in 0f..0.9f)
  val color=Color.parseColor(c.optString("background_color"));val accent=Color.parseColor(c.optString("accent"))
  val enabled=c.optBoolean("enabled");return Skin(revision,enabled,name,color,accent,shade,if(enabled)image(c.optString("wide_logo"),900000,false)else null,if(enabled)image(c.optString("icon_logo"),900000,false)else null,if(enabled)image(c.optString("background"),300000,true)else null,c.optBoolean("menu_accent",false))
 }
 private fun image(data:String,limit:Int,background:Boolean):Bitmap?{
  if(data.isBlank())return null
  val prefix = if (background) {
   "data:image/jpeg;base64,"
  } else {
   "data:image/png;base64,"
  }
  check(data.startsWith(prefix))
  val bytes=Base64.decode(data.substring(prefix.length),Base64.DEFAULT);check(bytes.size<=limit)
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
  check(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth<=2048&&bounds.outHeight<=2048&&bounds.outWidth.toLong()*bounds.outHeight<=2073600L)
  val options=BitmapFactory.Options().apply{inSampleSize=if(bounds.outWidth>1024||bounds.outHeight>1024)2 else 1;inPreferredConfig=if(background)Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888}
  return BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:error("Image illisible")
 }
 private fun notifyUi(){handler.post{active.get()?.let{a->if(a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))paint(a)}}}
 private fun paint(a:BaseActivity){
  if(!eligible(a)||a.isFinishing||a.isDestroyed)return
  val content=a.findViewById<ViewGroup>(android.R.id.content)?:return;val root=content.getChildAt(0)?:return;val s=skin
  if(!backgrounds.containsKey(root))backgrounds[root]=root.background
  if(s?.enabled==true){root.background=Wallpaper(s.background,s.color,s.shade)}else root.background=backgrounds[root]
  fun visit(v:View){
   val tag=v.tag as? String
   if(tag=="brand:header"&&v is ViewGroup){
    val named=s?.enabled==true&&s.name.isNotBlank()&&s.logo==null
    for(i in 0 until v.childCount){val child=v.getChildAt(i);if(child.tag=="brand:wide")child.visibility=if(named)View.GONE else View.VISIBLE;if(child.tag=="brand:name")child.visibility=if(named)View.VISIBLE else View.GONE}
   }
   if(tag=="brand:home-card"){
    if(!cardBackgrounds.containsKey(v))cardBackgrounds[v]=v.background
    if(s?.enabled==true&&s.menuAccent)v.background=officialHomeCard(s.accent,a.resources.displayMetrics.density)
    else v.background=cardBackgrounds[v]
   }
   if(tag?.startsWith("brand:setup-")==true){
    if(!cardBackgrounds.containsKey(v))cardBackgrounds[v]=v.background
    v.background=if(s?.enabled==true)setupBackground(tag,s.accent,a.resources.displayMetrics.density) else cardBackgrounds[v]
   }
   if(v is ImageView&&tag=="brand:accent-icon"){
    if(!iconTints.containsKey(v))iconTints[v]=v.imageTintList
    v.imageTintList=if(s?.enabled==true&&s.menuAccent)ColorStateList.valueOf(s.accent)else iconTints[v]
   }
   if(v is ImageView&&(tag=="brand:wide"||tag=="brand:icon")){
    if(!images.containsKey(v))images[v]=v.drawable
    val bitmap=if(tag=="brand:wide")s?.logo else s?.icon
    if(s?.enabled==true&&bitmap!=null){v.setImageBitmap(bitmap);v.scaleType=ImageView.ScaleType.FIT_CENTER}else v.setImageDrawable(images[v])
   }
   if(v is TextView&&(tag=="brand:name"||tag=="brand:accent")){
    if(!texts.containsKey(v))texts[v]=Pair(v.text,v.currentTextColor)
    val original=texts[v]!!
    v.text=if(s?.enabled==true&&tag=="brand:name"&&s.name.isNotBlank())s.name else original.first
    v.setTextColor(if(s?.enabled==true)s.accent else original.second)
   }
   if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))
  }
  visit(root)
 }
 private fun officialHomeCard(accent:Int,density:Float):Drawable{
  val dark=Color.rgb((Color.red(accent)*0.72f).toInt(),(Color.green(accent)*0.72f).toInt(),(Color.blue(accent)*0.72f).toInt())
  fun card(stroke:Boolean)=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(accent,dark,Color.rgb(14,14,18))).apply{
   cornerRadius=22f*density
   if(stroke)setStroke((3*density).toInt().coerceAtLeast(1),Color.argb(153,255,255,255))
  }
  return StateListDrawable().apply{
   addState(intArrayOf(android.R.attr.state_focused),card(true))
   addState(intArrayOf(android.R.attr.state_pressed),card(true))
   addState(intArrayOf(),card(false))
  }
 }
 private fun setupBackground(tag:String,accent:Int,density:Float):Drawable{
  fun soft(alpha:Int)=Color.argb(alpha,Color.red(accent),Color.green(accent),Color.blue(accent))
  fun shape(color:Int,radius:Float,stroke:Int=0,strokeColor:Int=Color.TRANSPARENT)=GradientDrawable().apply{
   setColor(color);cornerRadius=radius*density
   if(stroke>0)setStroke((stroke*density).toInt().coerceAtLeast(1),strokeColor)
  }
  fun selector(normal:Drawable,focused:Drawable,pressed:Drawable=focused)=StateListDrawable().apply{
   addState(intArrayOf(android.R.attr.state_focused),focused)
   addState(intArrayOf(android.R.attr.state_pressed),pressed)
   addState(intArrayOf(),normal)
  }
  return when(tag){
   "brand:setup-panel"->GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(22,27,33),Color.rgb(8,13,18))).apply{cornerRadius=24f*density;setStroke((2*density).toInt().coerceAtLeast(1),accent)}
   "brand:setup-input"->selector(shape(Color.rgb(7,13,18),12f,1,Color.rgb(75,82,92)),shape(Color.rgb(7,13,18),12f,3,accent))
   "brand:setup-primary"->selector(shape(accent,12f),shape(accent,12f,3,Color.WHITE),shape(soft(220),12f))
   "brand:setup-secondary"->selector(shape(Color.TRANSPARENT,10f),shape(soft(45),10f,2,accent))
   else->selector(shape(Color.rgb(20,25,31),20f,1,Color.rgb(55,64,76)),shape(soft(45),20f,3,accent),shape(soft(65),20f,2,accent))
  }
 }
 private class Wallpaper(private val bitmap:Bitmap?,private val color:Int,private val shade:Float):Drawable(){
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  override fun draw(canvas:Canvas){canvas.drawColor(color);bitmap?.let{b->val scale=kotlin.math.max(bounds.width().toFloat()/b.width,bounds.height().toFloat()/b.height);val w=b.width*scale;val h=b.height*scale;val left=bounds.left+(bounds.width()-w)/2;val top=bounds.top+(bounds.height()-h)/2;canvas.save();canvas.clipRect(bounds);canvas.drawBitmap(b,null,RectF(left,top,left+w,top+h),paint);canvas.restore()};canvas.drawColor(Color.argb((shade*255).toInt(),0,0,0))}
  override fun setAlpha(alpha:Int){}
  override fun setColorFilter(filter:ColorFilter?){}
  @Suppress("DEPRECATION") override fun getOpacity()=PixelFormat.OPAQUE
 }
}
