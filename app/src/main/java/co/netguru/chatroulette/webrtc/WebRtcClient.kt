package co.netguru.chatroulette.webrtc

import android.content.Context
import co.netguru.chatroulette.webrtc.constraints.AudioMediaConstraints
import co.netguru.chatroulette.webrtc.constraints.OfferAnswerConstraints
import co.netguru.chatroulette.webrtc.constraints.PeerConnectionConstraints
import org.webrtc.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

class WebRtcClient(context: Context) : RemoteVideoListener {

    private val counter = AtomicInteger(0)

    companion object {
        private const val INITIALIZE_AUDIO = true
        private const val INITIALIZE_VIDEO = true
        private const val HW_ACCELERATION = true

        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 24
    }

    private var remoteVideoStream: VideoTrack? = null

    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    private val audioSource: AudioSource
    private val localAudioTrack: AudioTrack

    private var remoteView: SurfaceViewRenderer? = null
    private var remoteVideoRenderer: VideoRenderer? = null
    private var localView: SurfaceViewRenderer? = null
    private var localVideoRenderer: VideoRenderer? = null

    private val eglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory

    private val audioConstraints by lazy {
        val audioConstraints = MediaConstraints()
        audioConstraints.mandatory.add(AudioMediaConstraints.DISABLE_AUDIO_PROCESSING.toKeyValuePair(true))
        audioConstraints
    }

    private val offerAnswerConstraints by lazy {
        val offerAnswerConstraints = MediaConstraints()
        offerAnswerConstraints.mandatory.add(OfferAnswerConstraints.OFFER_TO_RECEIVE_AUDIO.toKeyValuePair(true))
        offerAnswerConstraints.mandatory.add(OfferAnswerConstraints.OFFER_TO_RECEIVE_VIDEO.toKeyValuePair(true))
        offerAnswerConstraints
    }

    private val offerAnswerRestartConstraints by lazy {
        val offerAnswerConstraints = MediaConstraints()
        offerAnswerConstraints.mandatory.add(OfferAnswerConstraints.OFFER_TO_RECEIVE_AUDIO.toKeyValuePair(true))
        offerAnswerConstraints.mandatory.add(OfferAnswerConstraints.OFFER_TO_RECEIVE_VIDEO.toKeyValuePair(true))
        offerAnswerConstraints.mandatory.add(OfferAnswerConstraints.ICE_RESTART.toKeyValuePair(true))
        offerAnswerConstraints
    }

    private val peerConnectionConstraints by lazy {
        val peerConnectionConstraints = MediaConstraints()
        peerConnectionConstraints.mandatory.add(PeerConnectionConstraints.DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT.toKeyValuePair(true))
        peerConnectionConstraints.mandatory.add(PeerConnectionConstraints.GOOG_CPU_OVERUSE_DETECTION.toKeyValuePair(true))
        peerConnectionConstraints
    }

    private val videoCameraCapturer = WebRtcUtils.createCameraCapturerWithFrontAsDefault(context)
    var cameraEnabled = true
        set(isEnabled) {
            field = isEnabled
            videoCameraCapturer?.let { enableVideo(isEnabled, it) }
        }

    private lateinit var peerConnectionListener: PeerConnectionListener

    private val videoPeerConnectionListener by lazy { VideoPeerConnectionObserver(peerConnectionListener, this) }

    private lateinit var peerConnection: PeerConnection

    private lateinit var offeringPartyHandler: WebRtcOfferingPartyHandler
    private lateinit var answeringPartyHandler: WebRtcAnsweringPartyHandler

    init {
        if (!PeerConnectionFactory.initializeAndroidGlobals(context.applicationContext, INITIALIZE_AUDIO, INITIALIZE_VIDEO, HW_ACCELERATION)) {
            Timber.d("Failed to initializeAndroidGlobals")
        }
        peerConnectionFactory = PeerConnectionFactory(PeerConnectionFactory.Options())

        if (videoCameraCapturer != null) {
            peerConnectionFactory.setVideoHwAccelerationOptions(eglBase.eglBaseContext, eglBase.eglBaseContext)
            videoSource = peerConnectionFactory.createVideoSource(videoCameraCapturer)
            localVideoTrack = peerConnectionFactory.createVideoTrack(counter.getAndIncrement().toString(), videoSource)
            enableVideo(cameraEnabled, videoCameraCapturer)
        }

        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack(getCounterStringValueAndIncrement(), audioSource)
    }

    fun initializePeerConnection(iceServers: List<PeerConnection.IceServer>,
                                 peerConnectionListener: PeerConnectionListener,
                                 webRtcOfferingActionListener: WebRtcOfferingActionListener,
                                 webRtcAnsweringPartyListener: WebRtcAnsweringPartyListener) {

        this.peerConnectionListener = peerConnectionListener
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionConstraints, videoPeerConnectionListener)

        val stream = peerConnectionFactory.createLocalMediaStream(getCounterStringValueAndIncrement())

        stream.addTrack(localAudioTrack)
        localVideoTrack?.let { stream.addTrack(it) }

        peerConnection.addStream(stream)
        offeringPartyHandler = WebRtcOfferingPartyHandler(peerConnection, webRtcOfferingActionListener)
        answeringPartyHandler = WebRtcAnsweringPartyHandler(peerConnection, offerAnswerConstraints, webRtcAnsweringPartyListener)
    }

    override fun onAddRemoteVideoStream(remoteVideoTrack: VideoTrack) {
        remoteVideoStream = remoteVideoTrack
        remoteVideoRenderer?.let {
            remoteVideoTrack.addRenderer(it)
        }
    }

    override fun removeVideoStream() {
        remoteVideoStream = null
    }

    fun attachRemoteView(remoteView: SurfaceViewRenderer, renderListener: RendererCommon.RendererEvents? = null) {
        remoteView.init(eglBase.eglBaseContext, renderListener)
        this.remoteView = remoteView
        remoteVideoRenderer = VideoRenderer(remoteView)
        remoteVideoStream?.addRenderer(remoteVideoRenderer)
    }

    fun attachLocalView(localView: SurfaceViewRenderer, renderListener: RendererCommon.RendererEvents? = null) {
        localView.init(eglBase.eglBaseContext, renderListener)
        this.localView = localView
        localVideoRenderer = VideoRenderer(localView)
        localVideoTrack?.addRenderer(localVideoRenderer)
    }

    fun detachViews() {
        remoteView?.release()
        remoteVideoRenderer?.let { remoteVideoStream?.removeRenderer(it) }
        localView?.release()
        localVideoRenderer?.let { localVideoTrack?.removeRenderer(localVideoRenderer) }
    }

    fun dispose() {
        eglBase.release()
        audioSource.dispose()
        videoCameraCapturer?.dispose()
        videoSource?.dispose()
        peerConnectionFactory.dispose()
    }

    /**
     * If peer connection was initialized make sure that this method is called before [dispose]
     */
    fun releasePeerConnection() {
        peerConnection.close()
        peerConnection.dispose()
    }

    fun createOffer() {
        offeringPartyHandler.createOffer(offerAnswerConstraints)
    }

    fun handleRemoteAnswer(remoteSessionDescription: SessionDescription) {
        offeringPartyHandler.handleRemoteAnswer(remoteSessionDescription)
    }

    fun handleRemoteOffer(remoteSessionDescription: SessionDescription) {
        answeringPartyHandler.handleRemoteOffer(remoteSessionDescription)
    }

    fun addIceCandidate(iceCandidate: IceCandidate) {
        peerConnection.addIceCandidate(iceCandidate)
    }

    fun removeIceCandidate(iceCandidate: IceCandidate) {
        //todo move up to service manager or higher
        peerConnection.removeIceCandidates(arrayOf(iceCandidate))
    }

    fun restart() {
        offeringPartyHandler.createOffer(offerAnswerRestartConstraints)
    }

    fun switchCamera() = videoCameraCapturer?.switchCamera(null)

    private fun enableVideo(isEnabled: Boolean, videoCapturer: CameraVideoCapturer) {
        if (isEnabled)
            videoCapturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        else
            videoCapturer.stopCapture()
    }

    private fun getCounterStringValueAndIncrement() = counter.getAndIncrement().toString()
}