package com.kzplayer.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.UUID

/** Choix initial commun aux trois interfaces ; aucune connexion fournisseur supplémentaire. */
class PlaylistSetupActivity : BaseActivity() {
 private var busy=false
 private var leaving=false
 private lateinit var input:EditText
 private lateinit var status:TextView
 private val pending by lazy { getSharedPreferences("kz_active_code_v93",MODE_PRIVATE) }
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  setContentView(R.layout.activity_playlist_setup)
  input=findViewById(R.id.activeCodeInput)
  status=findViewById(R.id.setupStatus)
  findViewById<TextView>(R.id.setupLicense).text="Appareil : "+DeviceIdentity.licenseCode(this)
  findViewById<View>(R.id.chooseActiveCode).setOnClickListener{showActiveCode()}
  findViewById<View>(R.id.chooseManual).setOnClickListener{if(!busy)startActivity(Intent(this,AddPlaylistActivity::class.java))}
  findViewById<View>(R.id.submitActiveCode).setOnClickListener{redeem()}
  findViewById<View>(R.id.refreshSetup).setOnClickListener{refresh()}
  findViewById<View>(R.id.backToChoices).setOnClickListener{showChoices()}
  input.setOnEditorActionListener{_,action,_->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){redeem();true}else false}
  pending.getString("code",null)?.let{input.setText(it)}
  FocusFx.apply(findViewById<View>(R.id.chooseActiveCode),findViewById<View>(R.id.chooseManual),findViewById<View>(R.id.submitActiveCode),findViewById<View>(R.id.refreshSetup),findViewById<View>(R.id.backToChoices))
  findViewById<View>(R.id.chooseManual).requestFocus()
 }
 private fun showActiveCode(){if(busy)return;findViewById<View>(R.id.setupChooser).visibility=View.GONE;findViewById<View>(R.id.activeCodePanel).visibility=View.VISIBLE;status.text="";input.requestFocus()}
 private fun showChoices(){if(busy)return;findViewById<View>(R.id.activeCodePanel).visibility=View.GONE;findViewById<View>(R.id.setupChooser).visibility=View.VISIBLE;status.text="";findViewById<View>(R.id.chooseActiveCode).requestFocus()}
 override fun onResume(){super.onResume();if(Session.playlists.isNotEmpty())home()}
 private fun home(){if(leaving||isFinishing)return;leaving=true;pending.edit().clear().apply();startActivity(Intent(this,ThemePref.homeClass(this)));finish()}
 private fun loading(value:Boolean){busy=value;for(id in intArrayOf(R.id.chooseActiveCode,R.id.chooseManual,R.id.submitActiveCode,R.id.refreshSetup,R.id.backToChoices))findViewById<View>(id).isEnabled=!value;input.isEnabled=!value;findViewById<View>(R.id.setupProgress).visibility=if(value)View.VISIBLE else View.GONE}
 private fun applyPlaylists(playlists:List<Playlist>){Session.playlists=LocalPlaylists.merge(playlists);Session.current=Session.playlists.firstOrNull{it.id==Session.current?.id}?:Session.playlists.firstOrNull();SessionCache.save(this);if(Session.playlists.isNotEmpty()){pending.edit().clear().commit();home()}else status.text="Aucune playlist disponible. Choisis Active Code ou Manuel."}
 private fun redeem(){
  if(busy)return
  val code=input.text?.toString()?.trim().orEmpty()
  if(!Regex("^[0-9]{6}$").matches(code)){status.text="Saisis les 6 chiffres transmis par ton revendeur.";return}
  val license=DeviceIdentity.licenseCode(this)
  val request=if(pending.getString("code",null)==code&&pending.getString("license",null)==license)pending.getString("request",null)?:UUID.randomUUID().toString().replace("-","") else UUID.randomUUID().toString().replace("-","")
  if(!pending.edit().putString("code",code).putString("license",license).putString("request",request).commit()){status.text="Impossible de mémoriser la demande. Libère de l’espace puis réessaie.";return}
  loading(true);status.text="Récupération sécurisée de ta playlist…"
  lifecycleScope.launch{
   try{applyPlaylists(Api.redeemActiveCode(DeviceIdentity.stableId(this@PlaylistSetupActivity),license,code,request))}
   catch(e:kotlinx.coroutines.CancellationException){throw e}
   catch(e:Exception){status.text=(e.message?:"Réponse incertaine.")+"\nRéessaie avec le même code, ou utilise Actualiser. Ne communique pas tes identifiants IPTV."}
   finally{if(!isDestroyed)loading(false)}
  }
 }
 private fun refresh(){
  if(busy)return;loading(true);status.text="Vérification de la licence et des playlists…"
  lifecycleScope.launch{
   try{val r=Api.checkLicense(DeviceIdentity.stableId(this@PlaylistSetupActivity),DeviceIdentity.licenseCode(this@PlaylistSetupActivity),Build.MODEL?:"Android",BuildConfig.VERSION_NAME);if(r.ok&&r.active){Session.expiration=r.expiration;LicenseGuard.rememberOk(this@PlaylistSetupActivity,r.expiration);applyPlaylists(r.playlists)}else status.text=r.message.ifBlank{"La licence doit être active pour récupérer une playlist."}}
   catch(e:kotlinx.coroutines.CancellationException){throw e}
   catch(_:Exception){status.text="Impossible de vérifier la licence. Réessaie sans changer le code."}
   finally{if(!isDestroyed)loading(false)}
  }
 }
}
