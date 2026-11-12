package com.webrtc.scrcpy.core.webrtc

import android.content.Context
import android.util.Log
import com.webrtc.scrcpy.core.capture.MediaProjectionVideoCapturer
import com.webrtc.scrcpy.core.capture.ScreenGeometry
import com.webrtc.scrcpy.core.input.InputInjector
import com.webrtc.scrcpy.core.protocol.ControlEvent
import com.webrtc.scrcpy.core.protocol.ControlProtocolDecoder
import org.webrtc.*
import java.nio.ByteBuffer

/**
 * Core Android WebRTC engine
 */
class WebRtcEngine(
    private val context: Context,
    private val inputInjector: InputInjector
) {
    private val TAG = "WebRtcEngine"

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null

    var onLocalIceCandidate: ((IceCandidate) -> Unit)? = null
    var onSessionReady: (() -> Unit)? = null

    init {
        initWebRtc()
    }

    private fun initWebRtc() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .setNativeLibraryLoader { name ->
                try {
                    System.load("/data/local/tmp/libjingle_peerconnection_so.so")
                    true
                } catch (e: Throwable) {
                    try {
                        System.loadLibrary(name)
                        true
                    } catch (t: Throwable) {
                        false
                    }
                }
            }
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        eglBase = try {
            val clazz = Class.forName("org.webrtc.EglBase14Impl")
            val ctor = clazz.getDeclaredConstructor(android.opengl.EGLContext::class.java, IntArray::class.java)
            ctor.isAccessible = true
            ctor.newInstance(null, EglBase.CONFIG_PLAIN) as EglBase
        } catch (t: Throwable) {
            EglBase.create()
        }

        val encoderFactory = object : VideoEncoderFactory {
            private val hwFactory = HardwareVideoEncoderFactory(eglBase?.eglBaseContext, false, false)
            private val swFactory = SoftwareVideoEncoderFactory()

            private val swCodecs: Array<VideoCodecInfo> = swFactory.supportedCodecs
                .sortedBy { codec ->
                    when (codec.name.uppercase()) {
                        "VP8" -> 0
                        "H264" -> 1
                        "VP9" -> 2
                        else -> 3
                    }
                }.toTypedArray()

            /*
             * Prefer VP8 software encoder:
             * Note: LibvpxVp8Encoder extends WrappedNativeVideoEncoder; calling getImplementationName() on it
             * throws UnsupportedOperationException("Not implemented.") and triggers a fatal JNI CheckException SIGABRT crash,
             * so never invoke swEncoder.implementationName here.
             */
            override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
                swFactory.createEncoder(info)?.let { swEncoder ->
                    Log.i(TAG, "Enabled low-latency software encoder: ${info.name}")
                    return swEncoder
                }
                return if (swCodecs.isEmpty()) hwFactory.createEncoder(info) else null
            }

            override fun getSupportedCodecs(): Array<VideoCodecInfo> {
                if (swCodecs.isNotEmpty()) return swCodecs
                return hwFactory.supportedCodecs
            }

            override fun getImplementations(): Array<VideoCodecInfo> {
                return getSupportedCodecs()
            }

            override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? {
                return null
            }
        }
        val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        val pcfOptions = PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = true
        }

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(pcfOptions)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.i(TAG, "WebRTC PeerConnectionFactory initialized")
    }

    /**
     * Start screen video capture stream
     */
    fun startScreenStream(capturer: VideoCapturer, width: Int, height: Int, fps: Int) {
        this.videoCapturer = capturer
        val pcf = peerConnectionFactory ?: return

        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase?.eglBaseContext)
        videoSource = pcf.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        capturer.startCapture(width, height, fps)

        videoTrack = pcf.createVideoTrack("video_track_screen", videoSource)
        videoTrack?.setEnabled(true)

        // Default audio source
        val audioConstraints = MediaConstraints()
        audioSource = pcf.createAudioSource(audioConstraints)
        audioTrack = pcf.createAudioTrack("audio_track_system", audioSource)
        audioTrack?.setEnabled(true)

        // Touches are normalised over the frame, so map them onto the real display size
        val displaySize = ScreenGeometry.realDisplaySize(context)
        val inputWidth = displaySize?.width ?: width
        val inputHeight = displaySize?.height ?: height
        inputInjector.setScreenDimensions(inputWidth, inputHeight)
        Log.i(TAG, "Started video and audio tracks: capture=${width}x${height} @ ${fps}fps, touch=${inputWidth}x${inputHeight}")
    }

    /**
     * Handle incoming SDP Offer from signaling and generate an Answer
     */
    @Synchronized
    fun handleOffer(offerSdp: String, onAnswerCreated: (SessionDescription) -> Unit) {
        // Close and dispose any previous PeerConnection on browser refresh so old sessions do not hold encoder/bandwidth
        peerConnection?.let { oldPc ->
            try {
                oldPc.close()
                oldPc.dispose()
                Log.i(TAG, "Disposed previous PeerConnection for new session")
            } catch (e: Exception) {
                Log.w(TAG, "Error disposing previous PeerConnection: ${e.message}")
            }
            peerConnection = null
        }

        // Skip external Google STUN for direct LAN connections to avoid STUN timeouts slowing connection/refresh
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        val createdPc = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { onLocalIceCandidate?.invoke(it) }
            }

            override fun onDataChannel(dataChannel: DataChannel?) {
                dataChannel?.let { setupDataChannel(it) }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "ICE Connection State: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    (videoCapturer as? MediaProjectionVideoCapturer)?.requestRefreshFrame()
                }
            }

            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "Standardized ICE State: $state")
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "PeerConnection State: $state")
            }

            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: return
        peerConnection = createdPc

        val remoteSession = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        createdPc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                try {
                    var videoSender: RtpSender? = null
                    var audioTrackAssigned = false

                    createdPc.transceivers?.forEach { transceiver ->
                        if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                            videoTrack?.let {
                                transceiver.sender.setTrack(it, false)
                                transceiver.sender.setStreams(listOf("stream_screen"))
                                videoSender = transceiver.sender
                            }
                            transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                        } else if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                            audioTrack?.let {
                                transceiver.sender.setTrack(it, false)
                                transceiver.sender.setStreams(listOf("stream_screen"))
                                audioTrackAssigned = true
                            }
                            transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                        }
                    }

                    // Fallback for older clients or unmatched transceivers
                    if (videoSender == null && videoTrack != null) {
                        videoSender = createdPc.addTrack(videoTrack, listOf("stream_screen"))
                    }
                    if (!audioTrackAssigned && audioTrack != null) {
                        createdPc.addTrack(audioTrack, listOf("stream_screen"))
                    }

                    videoSender?.let { sender ->
                        try {
                            val params = sender.parameters
                            params.degradationPreference = RtpParameters.DegradationPreference.BALANCED
                            params.encodings.forEach { enc ->
                                enc.minBitrateBps = 800_000
                                enc.maxBitrateBps = 2_500_000
                                enc.maxFramerate = 30
                            }
                            sender.parameters = params
                        } catch (e: Exception) {
                            Log.w(TAG, "Note configuring video encoding parameters: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Note configuring transceiver direction: ${e.message}")
                }

                val constraints = MediaConstraints()
                createdPc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        answer?.let { rawAns ->
                            val boostedSdp = boostSdpVideoBitrate(rawAns.description)
                            val ans = SessionDescription(rawAns.type, boostedSdp)
                            createdPc.setLocalDescription(object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    onAnswerCreated(ans)
                                    (videoCapturer as? MediaProjectionVideoCapturer)?.requestRefreshFrame()
                                }
                            }, ans)
                        }
                    }
                }, constraints)
            }
        }, remoteSession)
    }

    /**
     * Inject moderate initial bitrate hints into the SDP Answer to prevent 300kbps startup drops without saturating Wi-Fi
     */
    private fun boostSdpVideoBitrate(sdp: String): String {
        val bitrateParams = "x-google-start-bitrate=1500;x-google-min-bitrate=800;x-google-max-bitrate=2500"
        val lines = sdp.split("\r\n").toMutableList()
        var inVideo = false
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("m=video")) {
                inVideo = true
            } else if (line.startsWith("m=")) {
                inVideo = false
            } else if (inVideo && line.startsWith("a=fmtp:") && !line.contains("x-google-start-bitrate")) {
                lines[i] = "$line;$bitrateParams"
            }
        }
        return lines.joinToString("\r\n")
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    /**
     * Bind control DataChannel and message handler
     */
    private fun setupDataChannel(dc: DataChannel) {
        Log.i(TAG, "Received peer RTCDataChannel: ${dc.label()}")
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {}
            override fun onStateChange() {
                Log.i(TAG, "DataChannel State: ${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val event = ControlProtocolDecoder.decode(buffer.data) ?: return

                when (event) {
                    is ControlEvent.Touch -> inputInjector.injectTouch(event)
                    is ControlEvent.Key -> inputInjector.injectKey(event)
                    is ControlEvent.Scroll -> inputInjector.injectScroll(event)
                    is ControlEvent.Text -> inputInjector.injectText(event.text)
                    is ControlEvent.Command -> inputInjector.injectCommand(event.commandId)
                }
            }
        })
    }

    fun release() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnection?.close()
            peerConnectionFactory?.dispose()
            eglBase?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WebRtcEngine: ${e.message}")
        }
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) { Log.e("WebRtcEngine", "SDP Create Failure: $s") }
        override fun onSetFailure(s: String?) { Log.e("WebRtcEngine", "SDP Set Failure: $s") }
    }
}

