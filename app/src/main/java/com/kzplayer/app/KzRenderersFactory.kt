package com.kzplayer.app

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

// v147 : usine de renderers ExoPlayer specifique KZ Player.
//
// But : reparer les box TV ou l'image reste figee sur la premiere frame
// (son OK) en donnant la priorite au decodeur video LOGICIEL. Le decodeur
// logiciel systeme (c2.android.avc.decoder / c2.android.hevc.decoder) est
// present sur tous les Android depuis Lollipop et decode les flux IPTV
// live/VOD sans se figer, contrairement aux decodeurs materiels vendor
// qui plantent silencieusement sur certains firmwares.
//
// Le mode est controle par VideoDecoderPref (AUTO / SOFTWARE / HARDWARE) :
// - AUTO       : logiciel prioritaire, materiel en secours (defaut).
// - SOFTWARE   : force le logiciel (peut demander un peu plus de CPU).
// - HARDWARE   : force le materiel (rapide, mais peut figer).
//
// Notes :
// - N'affecte QUE la video. L'audio reste inchange (FFmpeg extension pour
//   AC3/EAC3/DTS toujours preferee sur le live via EXTENSION_RENDERER_MODE_PREFER).
// - Ne casse PAS ExoPlayer : on garde tout le pipeline standard
//   (extracteurs TS Stalker, MediaCodec renderer, DefaultTrackSelector, etc.),
//   on change seulement l'ORDRE de selection des decodeurs video.
// - setEnableDecoderFallback(true) est conserve : si le decodeur choisi
//   echoue a l'init, ExoPlayer bascule automatiquement sur le suivant.
// v382 : parametre forceHardware. Utilise UNIQUEMENT pour les chaines Full HD
// (1080p), qui saccadent avec le decodeur logiciel car elles demandent trop de
// calcul. Pour toutes les autres chaines, rien ne change : le decodeur logiciel
// reste prioritaire comme avant (c est lui qui evite l image figee).
// Le logiciel reste place juste derriere en secours, et setEnableDecoderFallback(true)
// bascule dessus automatiquement si le materiel refuse le flux.
class KzRenderersFactory(
    private val ctx: Context,
    private val forceHardware: Boolean = false
) : DefaultRenderersFactory(ctx) {

    init {
        // Repli automatique sur un autre decodeur en cas d'echec d'init.
        setEnableDecoderFallback(true)

        // Selecteur de decodeur video base sur VideoDecoderPref.
        // Pour l'audio, on renvoie la liste par defaut sans reordonner (pour ne
        // pas casser la preference FFmpeg AC3/EAC3/DTS cote live/VOD).
        setMediaCodecSelector(object : MediaCodecSelector {
            @Throws(MediaCodecUtil.DecoderQueryException::class)
            override fun getDecoderInfos(
                mimeType: String,
                requiresSecureDecoder: Boolean,
                requiresTunnelingDecoder: Boolean
            ): List<MediaCodecInfo> {
                val all = try {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                } catch (_: Throwable) { emptyList() }

                // Audio ou aucun decodeur : on garde l'ordre par defaut.
                if (!mimeType.startsWith("video/") || all.isEmpty()) return all

                val sw = all.filter { it.softwareOnly }
                val hw = all.filter { !it.softwareOnly }

                // v382 : chaine Full HD -> materiel d abord (fluidite), logiciel en secours.
                if (forceHardware && VideoDecoderPref.current(ctx) != VideoDecoderPref.SOFTWARE) {
                    return if (hw.isNotEmpty()) hw + sw else all
                }

                return when (VideoDecoderPref.current(ctx)) {
                    VideoDecoderPref.HARDWARE -> {
                        // Materiel d'abord, logiciel en secours si le HW echoue.
                        if (hw.isNotEmpty()) hw + sw else all
                    }
                    VideoDecoderPref.SOFTWARE -> {
                        // On force le logiciel : si aucun logiciel dispo (rare),
                        // on rebascule sur la liste complete pour ne pas casser la lecture.
                        if (sw.isNotEmpty()) sw else all
                    }
                    else -> {
                        // v390 : AUTO = MATERIEL en premier pour TOUT LE MONDE.
                        // Avant, le logiciel passait devant : c est lui qui provoquait
                        // les saccades sur les chaines HD et Full HD (le nom de la chaine
                        // ne permet pas de deviner sa qualite). Le logiciel reste juste
                        // derriere en secours immediat, et si l image se fige vraiment
                        // 2 fois sur cet appareil, l appli bascule elle-meme en logiciel
                        // et le retient (VideoDecoderPref.autoSoftware).
                        if (VideoDecoderPref.autoSoftware(ctx)) {
                            if (sw.isNotEmpty()) sw + hw else all
                        } else {
                            if (hw.isNotEmpty()) hw + sw else all
                        }
                    }
                }
            }
        })
    }
}
