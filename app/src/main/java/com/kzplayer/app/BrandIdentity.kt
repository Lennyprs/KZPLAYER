package com.kzplayer.app
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
object BrandIdentity {
 private val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
 @Volatile private var expectedHash=""
 @Volatile private var expectedVersion=0
 suspend fun check(current:String,code:Int):UpdateInfo=withContext(Dispatchers.IO){
  try{
   val form=FormBody.Builder().add("action","brandUpdateGet").add("brand_id",BuildConfig.BRAND_ID).build()
   val text=client.newCall(Request.Builder().url(Config.LOGIN_URL).post(form).build()).execute().use{response->kotlin.check(response.isSuccessful);response.body?.string().orEmpty()}
   val obj=JSONObject(text);kotlin.check(obj.optBoolean("ok")&&obj.optString("brand_id")==BuildConfig.BRAND_ID)
   val version=obj.optInt("version_code")
   if(version<=code)return@withContext UpdateInfo(true,false,current,current,"","","")
   kotlin.check(obj.optString("package_id")==BuildConfig.APPLICATION_ID)
   val url=obj.optString("apk_url")
   val expected="https://github.com/"+"Lennyprs/KZPLAYER/releases/download/brand-"+BuildConfig.BRAND_ID+"/app-"+BuildConfig.BRAND_ID+"-"+version+".apk"
   kotlin.check(url==expected)
   val hash=obj.optString("sha256");kotlin.check(hash.matches(Regex("[a-f0-9]{64}")))
   expectedHash=hash;expectedVersion=version
   UpdateInfo(true,true,obj.optString("version_name"),current,url,obj.optString("notes"),"")
  }catch(e:Exception){UpdateInfo(false,false,"",current,"","","Canal de marque indisponible. Réessaie plus tard.")}
 }
 @Suppress("DEPRECATION") fun verify(ctx:Context,file:File){
  if(BuildConfig.BRAND_ID.isBlank())return
  kotlin.check(expectedHash.isNotBlank()&&expectedVersion>0){"Vérifie d'abord la mise à jour."}
  val digest=MessageDigest.getInstance("SHA-256")
  file.inputStream().use{stream->val buffer=ByteArray(65536);while(true){val count=stream.read(buffer);if(count<0)break;digest.update(buffer,0,count)}}
  val hash=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
  kotlin.check(hash==expectedHash){"Intégrité APK incorrecte"}
  val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
  val archive=ctx.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:error("APK illisible")
  kotlin.check(archive.packageName==ctx.packageName){"APK d'une autre application"}
  val version=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else archive.versionCode.toLong()
  kotlin.check(version==expectedVersion.toLong()){"Version APK incorrecte"}
  val signatures=if(Build.VERSION.SDK_INT>=28)archive.signingInfo?.apkContentsSigners else archive.signatures
  val cert=signatures?.firstOrNull()?.toByteArray()?:error("Signature absente")
  val sha=MessageDigest.getInstance("SHA-256").digest(cert).joinToString(""){"%02X".format(it.toInt() and 255)}
  kotlin.check(sha==SecurityChecks.currentSignatureSha256(ctx)){"Signature incorrecte"}
 }
}
