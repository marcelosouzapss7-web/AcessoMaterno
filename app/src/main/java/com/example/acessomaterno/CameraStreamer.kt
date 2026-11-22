package com.example.acessomaterno

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * CameraStreamer — câmera do dispositivo em H.264 (MediaCodec, encoder de
 * HARDWARE) transmitido pelo Socket.IO (evento "camera_h264") para a janela
 * "View" do emulador nativo. Substitui o antigo JPEG-por-frame (que travava):
 *
 *  Câmera2 → Surface de entrada do MediaCodec (AVC) → NAL H.264 → socket.
 *
 * Por que não trava mais:
 *  • Encoder de hardware alimentado direto pelo Surface da câmera (sem JPEG na
 *    CPU) → fluxo contínuo e suave, em frontal OU traseira.
 *  • KEYFRAME periódico (a cada ~1s) + SPS/PPS prefixado a CADA keyframe → quem
 *    assiste resincroniza sozinho em no máx. ~1s se perder um pedaço (antes só
 *    "destravava" ao trocar de câmera).
 *  • Sala/decoder próprios no lado do PC (PyAV) — não mexe no preview, no
 *    emulador nativo nem no FPS.
 *
 * Decodificação no PC: PyAV (av) na janela "View" (JanelaCamera).
 */
object CameraStreamer {
    private const val TAG = "CameraStreamer"
    private const val LARGURA_ALVO = 1280
    private const val ALTURA_ALVO = 720
    private const val BITRATE = 3_000_000       // ~3 Mbps (boa qualidade)
    private const val FPS = 30
    private const val IFRAME_INTERVALO_S = 1     // keyframe a cada 1s

    @Volatile private var rodando = false
    @Volatile private var frontal = true
    private var cameraDevice: CameraDevice? = null
    private var session: android.hardware.camera2.CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var drainThread: Thread? = null
    @Volatile private var csd: ByteArray? = null   // SPS+PPS (config)

    @get:Synchronized val estaRodando: Boolean get() = rodando

    @Synchronized
    fun iniciar(ctx: Context, frontal: Boolean = true) {
        this.frontal = frontal
        if (rodando) { reabrir(ctx); return }
        rodando = true
        camThread = HandlerThread("camera-h264").also { it.start() }
        camHandler = Handler(camThread!!.looper)
        abrir(ctx)
    }

    @Synchronized
    fun trocar(ctx: Context) {
        frontal = !frontal
        if (rodando) reabrir(ctx) else iniciar(ctx, frontal)
    }

    @Synchronized
    fun parar() {
        rodando = false
        fecharPipeline()
        try { camThread?.quitSafely() } catch (_: Exception) {}
        camThread = null; camHandler = null
        Log.i(TAG, "Câmera (H.264) parada.")
    }

    /** Pede um keyframe imediato ao encoder (resync sob demanda). */
    fun pedirKeyframe() {
        try {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {}
    }

    private fun reabrir(ctx: Context) {
        fecharPipeline()
        abrir(ctx)
    }

    /** Reabre a câmera sozinho após uma queda (lock/unlock, reboot, câmera
     *  temporariamente ocupada), enquanto o View continuar ligado. Tenta de novo
     *  a cada ~1,5s — fluxo "infinito" e em tempo real, sem precisar reclicar. */
    private fun reagendarReabertura(ctx: Context) {
        if (!rodando) return
        val h = camHandler ?: return
        h.postDelayed({
            if (rodando && cameraDevice == null) {
                try { reabrir(ctx) } catch (e: Exception) {
                    Log.e(TAG, "Falha ao reabrir câmera: ${e.message}")
                }
            }
        }, 1500L)
    }

    private fun fecharPipeline() {
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        session = null; cameraDevice = null; encoder = null; inputSurface = null
        csd = null
        try { drainThread?.join(500) } catch (_: Exception) {}
        drainThread = null
    }

    private fun temPermissaoCamera(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun abrir(ctx: Context) {
        if (!temPermissaoCamera(ctx)) {
            SocketManager.emitLogPublico("[CAMERA] IGNORADO: permissão de câmera não concedida.")
            return
        }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            val idAlvo = escolherCamera(cm, frontal) ?: run {
                Log.e(TAG, "Nenhuma câmera encontrada."); return
            }
            val chars = cm.getCameraCharacteristics(idAlvo)
            val tamanho = escolherTamanho(chars)

            // 1) Encoder H.264 com Surface de entrada (alimentado pela câmera).
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, tamanho.width, tamanho.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVALO_S)
                try { setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) } catch (_: Exception) {}
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            encoder = enc

            // 2) Thread que drena o encoder e emite os NALs H.264.
            drainThread = Thread({ loopDrenar() }, "camera-h264-drain").also { it.start() }

            // 3) Abre a câmera mirando o Surface do encoder.
            cm.openCamera(idAlvo, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    criarSessao(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    try { device.close() } catch (_: Exception) {}
                    if (cameraDevice === device) cameraDevice = null
                    reagendarReabertura(ctx)
                }
                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "Erro ao abrir câmera: $error")
                    try { device.close() } catch (_: Exception) {}
                    if (cameraDevice === device) cameraDevice = null
                    reagendarReabertura(ctx)
                }
            }, camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao abrir câmera H.264: ${e.message}")
            SocketManager.emitLogPublico("[CAMERA] Falha ao abrir (H.264): ${e.message}")
        }
    }

    private fun criarSessao(device: CameraDevice) {
        val surface = inputSurface ?: return
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(FPS, FPS))
            }
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(req.build(), null, camHandler)
                        SocketManager.emitLogPublico("[CAMERA] Streaming H.264 iniciado (${if (frontal) "frontal" else "traseira"}).")
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro no setRepeatingRequest: ${e.message}")
                    }
                }
                override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                    Log.e(TAG, "Falha ao configurar sessão da câmera.")
                }
            }, camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar sessão: ${e.message}")
        }
    }

    private fun loopDrenar() {
        val info = MediaCodec.BufferInfo()
        while (rodando) {
            val enc = encoder ?: break
            try {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        val ehConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (ehConfig) {
                            csd = data   // SPS/PPS — guarda para prefixar nos keyframes
                        } else {
                            val keyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val payload = if (keyframe && csd != null) csd!! + data else data
                            SocketManager.enviarCameraH264(payload)
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Alguns aparelhos entregam o SPS/PPS aqui (csd-0 + csd-1) em
                    // vez de num buffer CODEC_CONFIG. Capturamos para prefixar
                    // nos keyframes (resync de quem entra no meio do stream).
                    try {
                        val f = enc.outputFormat
                        val c0 = f.getByteBuffer("csd-0")
                        val c1 = f.getByteBuffer("csd-1")
                        if (c0 != null) {
                            val b0 = ByteArray(c0.remaining()); c0.get(b0)
                            val b1 = if (c1 != null) { val t = ByteArray(c1.remaining()); c1.get(t); t } else ByteArray(0)
                            csd = b0 + b1
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                if (rodando) Log.e(TAG, "Erro ao drenar encoder: ${e.message}")
                break
            }
        }
    }

    private fun escolherCamera(cm: CameraManager, frontal: Boolean): String? {
        val desejado = if (frontal) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        var fallback: String? = null
        for (id in cm.cameraIdList) {
            fallback = fallback ?: id
            if (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == desejado) return id
        }
        return fallback
    }

    private fun escolherTamanho(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(LARGURA_ALVO, ALTURA_ALVO)
        // Tamanhos suportados para encoder (Surface do MediaCodec).
        val tamanhos = try { map.getOutputSizes(MediaCodec::class.java) } catch (_: Exception) { null }
            ?: return Size(LARGURA_ALVO, ALTURA_ALVO)
        val alvoArea = LARGURA_ALVO * ALTURA_ALVO
        return tamanhos
            .filter { it.width <= 1920 && it.height <= 1920 }
            .minByOrNull { Math.abs(it.width * it.height - alvoArea) }
            ?: Size(LARGURA_ALVO, ALTURA_ALVO)
    }
}
