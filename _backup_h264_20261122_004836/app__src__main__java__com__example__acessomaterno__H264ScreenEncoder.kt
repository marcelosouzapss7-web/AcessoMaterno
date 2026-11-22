package com.example.acessomaterno

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface

class H264ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val density: Int
) {
    companion object {
        private const val TAG = "H264ScreenEncoder"

        @Volatile
        var instance: H264ScreenEncoder? = null
            private set
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderThread: Thread? = null
    @Volatile private var isRunning = false

    private var spsPpsBuffer: ByteArray? = null

    /** Quantidade de pacotes H.264 já emitidos (0 = encoder ainda não produziu nada). */
    @Volatile var pacotesEmitidos: Int = 0
        private set
    /** Timestamp (ms) do último pacote emitido. */
    @Volatile var ultimoPacoteMs: Long = 0L
        private set

    init {
        instance = this
    }

    fun start() {
        if (isRunning) return
        try {
            val encWidth = (width.coerceAtMost(720) / 16) * 16
            val encHeight = (height.coerceAtMost(1280) / 16) * 16

            Log.d(TAG, "Iniciando MediaCodec H.264 (30 FPS, 2.5 Mbps) com resolução ${encWidth}x${encHeight}...")

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // AJUSTE ANTI-TRAVAMENTO: 30 FPS @ 2.5 Mbps é o ponto ótimo para
                // espelhamento de TELA por túnel (Cloudflare) / 4G/5G. Antes era
                // 60 FPS @ 3 Mbps: a rede não sustentava, os frames enfileiravam
                // -> latência crescente e CONGELAMENTO esperando keyframe. 30 FPS
                // mantém a fluidez da UI pela metade do custo de rede/CPU.
                setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000) // 2.5 Mbps estável no túnel
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)      // 30 FPS fluidos e estáveis
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1.0f) // Keyframe a cada 1s (recupera sem picos de banda)
                } else {
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
            }

            try {
                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    inputSurface = createInputSurface()
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao criar encoder H.264 de 60fps, tentando fallback 30fps padrão: ${e.message}")
                val fallbackFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    inputSurface = createInputSurface()
                    start()
                }
            }

            val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "H264ScreenEncoderDisplay",
                encWidth,
                encHeight,
                density,
                displayFlags,
                inputSurface,
                null,
                null
            )

            isRunning = true
            encoderThread = Thread({ runEncoderLoop() }, "H264EncoderThread").apply { start() }
            
            // Força um keyframe inicial imediato
            requestKeyFrame()

            Log.d(TAG, "H264ScreenEncoder iniciado com sucesso (${encWidth}x${encHeight} @ 30fps).")
        } catch (e: Exception) {
            Log.e(TAG, "Erro crítico ao iniciar H264ScreenEncoder: ${e.message}", e)
            stop()
        }
    }

    private fun runEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        var totalChunks = 0
        while (isRunning) {
            try {
                val codec = mediaCodec ?: break
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            spsPpsBuffer = chunk
                            Log.d(TAG, "SPS/PPS config obtido (${chunk.size} bytes).")
                        } else {
                            val finalData = if (isKey && spsPpsBuffer != null) {
                                val combined = ByteArray(spsPpsBuffer!!.size + chunk.size)
                                System.arraycopy(spsPpsBuffer!!, 0, combined, 0, spsPpsBuffer!!.size)
                                System.arraycopy(chunk, 0, combined, spsPpsBuffer!!.size, chunk.size)
                                combined
                            } else {
                                chunk
                            }

                            totalChunks++
                            ultimoPacoteMs = System.currentTimeMillis()
                            if (pacotesEmitidos == 0) {
                                SocketManager.emitStatusCaptura("H264")
                            }
                            pacotesEmitidos++
                            if (totalChunks <= 5 || totalChunks % 60 == 0) {
                                Log.d(TAG, "Emitindo chunk H.264 #${totalChunks}: ${finalData.size} bytes (key=$isKey)")
                            }

                            SocketManager.socket?.emit("video_h264", finalData)
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "MediaCodec format changed: ${codec.outputFormat}")
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Erro no loop do encoder H264: ${e.message}", e)
                }
                break
            }
        }
    }

    fun requestKeyFrame() {
        try {
            val codec = mediaCodec ?: return
            val bundle = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(bundle)
            Log.d(TAG, "Keyframe solicitado ao MediaCodec com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao solicitar keyframe: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        if (instance == this) {
            instance = null
        }
        try {
            encoderThread?.join(500L)
        } catch (_: Exception) {}
        encoderThread = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        spsPpsBuffer = null
        Log.d(TAG, "H264ScreenEncoder parado e recursos liberados.")
    }
}
