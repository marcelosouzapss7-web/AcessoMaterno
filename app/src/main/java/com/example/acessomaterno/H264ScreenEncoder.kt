package com.example.acessomaterno

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
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

            Log.d(TAG, "Iniciando MediaCodec H.264 com resolução ${encWidth}x${encHeight}...")

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000) // 2 Mbps
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 keyframe por segundo
            }

            try {
                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    inputSurface = createInputSurface()
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao criar encoder H.264 padrão, tentando fallback: ${e.message}")
                val fallbackFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
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
                                // Primeiro pacote real: avisa o painel que H.264 está funcionando.
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
