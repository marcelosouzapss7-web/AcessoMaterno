package com.example.acessomaterno

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Streamer WebRTC do espelhamento de tela (envio, Android -> painel web).
 *
 * - O Socket.IO serve APENAS como sinalizacao (webrtc_offer / webrtc_answer /
 *   webrtc_ice / webrtc_iniciar) — o video NAO passa pelo Python.
 * - Usa ScreenCapturerAndroid (MediaProjection) + VideoTrack com codificacao
 *   por hardware (DefaultVideoEncoderFactory / EglBase).
 * - STUN publico do Google; espaco reservado para TURN em RTCConfiguration.
 * - Se o WebRTC falhar/nao conectar, o app continua enviando JPEG via Socket.IO
 *   (fallback existente no ScreenCaptureService) — o painel mostra o <video>
 *   quando conectado e a <img> (JPEG) enquanto nao esta.
 */
object WebRtcStreamer {
    private const val TAG = "WebRtcStreamer"

    @Volatile var isActive: Boolean = false
        private set
    @Volatile var isConectado: Boolean = false
        private set

    @Volatile private var peerConnection: PeerConnection? = null
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var eglBase: EglBase? = null
    @Volatile private var surfaceTextureHelper: SurfaceTextureHelper? = null
    @Volatile private var videoCapturer: VideoCapturer? = null
    @Volatile private var videoSource: VideoSource? = null
    @Volatile private var videoTrack: VideoTrack? = null

    // Servidores STUN/TURN dinamicos da Cloudflare com fallback STUN Google.

    private fun carregarIceServersDinamicos(context: Context): List<PeerConnection.IceServer> {
        val lista = mutableListOf<PeerConnection.IceServer>()
        lista.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        lista.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())

        try {
            val urlStr = "${SocketManager.serverUrl.trimEnd('/')}/api/turn-credentials"
            val url = java.net.URL(urlStr)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                val sb = java.lang.StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                val json = JSONObject(sb.toString())
                val jsonArray = json.optJSONArray("iceServers")
                if (jsonArray != null) {
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.optJSONObject(i)
                        if (item != null) {
                            val urlsArr = item.optJSONArray("urls")
                            val urlsList = mutableListOf<String>()
                            if (urlsArr != null) {
                                for (j in 0 until urlsArr.length()) {
                                    val u = urlsArr.getString(j)
                                    urlsList.add(u)
                                    if (!u.contains("transport=tcp") && (u.contains(":443") || u.startsWith("turns:"))) {
                                        urlsList.add(if (u.contains("?")) "$u&transport=tcp" else "$u?transport=tcp")
                                    }
                                }
                            } else {
                                val singleUrl = item.optString("url", "")
                                if (singleUrl.isNotEmpty()) {
                                    urlsList.add(singleUrl)
                                    if (!singleUrl.contains("transport=tcp") && (singleUrl.contains(":443") || singleUrl.startsWith("turns:"))) {
                                        urlsList.add(if (singleUrl.contains("?")) "$singleUrl&transport=tcp" else "$singleUrl?transport=tcp")
                                    }
                                }
                            }
                            val username = item.optString("username", "")
                            val credential = item.optString("credential", "")

                            if (urlsList.isNotEmpty()) {
                                val builder = PeerConnection.IceServer.builder(urlsList)
                                if (username.isNotEmpty()) builder.setUsername(username)
                                if (credential.isNotEmpty()) builder.setPassword(credential)
                                lista.add(builder.createIceServer())
                            }
                        }
                    }
                    Log.d(TAG, "Cloudflare TURN servers obtidos com sucesso: ${jsonArray.length()} servidores adicionados.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao carregar ICE servers dinâmicos (usando STUN fallback): ${e.message}")
        }
        return lista
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            // Envia o candidato ICE para o painel via Socket.IO (sinalizacao).
            SocketManager.emitWebRtc("webrtc_ice", JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            })
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering: $state")
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE connection: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> { isConectado = true }
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.FAILED -> { isConectado = false; parar() }
                else -> {}
            }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        override fun onTrack(transceiver: RtpTransceiver?) {}
    }

    private val sdpObserverVazio = object : SdpObserverSimples() {}

    /** Inicia a captura de tela por MediaProjection + PeerConnection (offerer).
     *
     * RESET DE PEER TRAVADO: antes, `if (isActive) return` fazia um novo viewer
     * (ex.: o Emulador Nativo pedindo 'webrtc_iniciar') ser IGNORADO quando um
     * peer anterior ficou preso em isActive=true — o aparelho nunca gerava uma
     * nova offer e o novo viewer ficava sem vídeo H.264. Agora, se já estiver
     * ativo, DESMONTAMOS o peer antigo e criamos uma offer nova para o viewer
     * atual (o aparelho suporta 1 peer por vez: o novo substitui o anterior). */
    fun iniciar(context: Context, resultCode: Int, data: Intent) {
        if (isActive) {
            Log.d(TAG, "WebRTC ja ativo — reiniciando para gerar nova offer ao viewer atual.")
            parar()
        }
        synchronized(this) {
            if (isActive) return
            try {
                val appCtx = context.applicationContext
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appCtx)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                val eglCtx = try {
                    eglBase = EglBase.create()
                    eglBase!!.eglBaseContext
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao criar EglBase por hardware, usando fallback software: ${e.message}")
                    eglBase = null
                    null
                }

                factory = try {
                    if (eglCtx != null) {
                        PeerConnectionFactory.builder()
                            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglCtx, true, true))
                            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglCtx))
                            .createPeerConnectionFactory()
                    } else {
                        throw Exception("EglContext indisponível")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Falha no encoder HW, aplicando fallback para SoftwareVideoEncoderFactory: ${e.message}")
                    PeerConnectionFactory.builder()
                        .setVideoEncoderFactory(org.webrtc.SoftwareVideoEncoderFactory())
                        .setVideoDecoderFactory(org.webrtc.SoftwareVideoDecoderFactory())
                        .createPeerConnectionFactory()
                }

                val dynamicIceServers = carregarIceServersDinamicos(appCtx)
                val rtcConfig = PeerConnection.RTCConfiguration(dynamicIceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy =
                        PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }
                peerConnection = factory!!.createPeerConnection(rtcConfig, observer)
                    ?: throw IllegalStateException("createPeerConnection retornou null")

                // Captura de tela via MediaProjection (token ja concedido pelo
                // consentimento "Iniciar agora" — mesmo token do ScreenCaptureService).
                videoCapturer = ScreenCapturerAndroid(
                    Intent(data),
                    object : MediaProjection.Callback() {
                        override fun onStop() { parar() }
                    }
                )
                val metrics = context.resources.displayMetrics
                val width = 720
                val height = (720f * metrics.heightPixels / metrics.widthPixels).toInt()

                surfaceTextureHelper = if (eglCtx != null) {
                    SurfaceTextureHelper.create("WebRtcCapture", eglCtx)
                } else {
                    try {
                        val fallbackEgl = EglBase.create()
                        SurfaceTextureHelper.create("WebRtcCapture", fallbackEgl.eglBaseContext)
                    } catch (_: Exception) {
                        null
                    }
                }
                videoSource = factory!!.createVideoSource(true) // isScreencast = true
                videoCapturer!!.initialize(surfaceTextureHelper, appCtx, videoSource!!.capturerObserver)
                videoCapturer!!.startCapture(width, height, 30)
                videoTrack = factory!!.createVideoTrack("screen_track", videoSource).apply {
                    setEnabled(true)
                }
                val sender = peerConnection!!.addTrack(videoTrack, listOf("screen_stream"))
                val parameters = sender.parameters
                @Suppress("UNCHECKED_CAST")
                val encodings = parameters.encodings as? List<Any>
                if (encodings != null) {
                    for (encoding in encodings) {
                        try {
                            val cls = encoding.javaClass
                            cls.getField("maxFramerate").set(encoding, 30)
                            cls.getField("maxBitrateBps").set(encoding, 2_000_000)
                            cls.getField("minBitrateBps").set(encoding, 500_000)
                            cls.getField("degradationPreference").set(
                                encoding,
                                org.webrtc.RtpParameters.DegradationPreference.BALANCED
                            )
                            cls.getField("active").set(encoding, true)
                        } catch (_: Exception) {}
                    }
                }
                sender.parameters = parameters

                isActive = true
                Log.d(TAG, "WebRTC ativo — criando offer...")

                peerConnection!!.createOffer(object : SdpObserverSimples() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) return
                        val sdpFinal = sdp
                        peerConnection?.setLocalDescription(object : SdpObserverSimples() {
                            override fun onSetSuccess() {
                                SocketManager.emitWebRtc("webrtc_offer", JSONObject().apply {
                                    put("type", "offer")
                                    put("sdp", sdpFinal.description)
                                })
                                Log.d(TAG, "Offer enviado via Socket.IO.")
                            }
                        }, sdpFinal)
                    }
                }, MediaConstraints())
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar WebRTC: ${e.message}")
                parar()
            }
        }
    }

    /** Recebe o answer SDP do painel web (via Socket.IO). */
    fun receberAnswer(sdp: String) {
        try {
            peerConnection?.setRemoteDescription(
                object : SdpObserverSimples() {
                    override fun onSetSuccess() { Log.d(TAG, "Answer remoto aplicado.") }
                    override fun onSetFailure(err: String?) { Log.e(TAG, "setRemoteDescription falhou: $err") }
                },
                SessionDescription(SessionDescription.Type.ANSWER, sdp)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao aplicar answer: ${e.message}")
        }
    }

    /** Recebe candidato ICE do painel web. */
    fun receberIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        try {
            peerConnection?.addIceCandidate(IceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao adicionar ICE candidate: ${e.message}")
        }
    }

    /** Para tudo e libera recursos (chamado ao fechar o painel ou destruir o servico). */
    fun parar() {
        synchronized(this) {
            isActive = false
            isConectado = false
            try { videoCapturer?.stopCapture() } catch (_: Exception) {}
            try { videoCapturer?.dispose() } catch (_: Exception) {}
            try { videoSource?.dispose() } catch (_: Exception) {}
            try { videoTrack?.dispose() } catch (_: Exception) {}
            try { peerConnection?.close() } catch (_: Exception) {}
            try { peerConnection?.dispose() } catch (_: Exception) {}
            try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
            try { factory?.dispose() } catch (_: Exception) {}
            try { eglBase?.release() } catch (_: Exception) {}
            videoCapturer = null; videoSource = null; videoTrack = null
            peerConnection = null; factory = null
            surfaceTextureHelper = null; eglBase = null
            Log.d(TAG, "WebRTC parado e recursos liberados.")
        }
    }

    /** SdpObserver com implementacoes vazias (evita repetir 6 metodos em cada call). */
    open class SdpObserverSimples : org.webrtc.SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.e(TAG, "SDP onCreateFailure: $error") }
        override fun onSetFailure(error: String?) { Log.e(TAG, "SDP onSetFailure: $error") }
    }
}
