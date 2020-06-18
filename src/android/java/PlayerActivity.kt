
package jp.rabee

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Pair
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.rubensousa.previewseekbar.exoplayer.PreviewTimeBar
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil
import com.google.android.exoplayer2.source.*
import com.google.android.exoplayer2.source.ads.AdsLoader
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.ErrorMessageProvider
import com.google.android.exoplayer2.util.EventLogger
import com.google.android.exoplayer2.util.Log
import com.google.android.exoplayer2.util.Util
import com.google.gson.GsonBuilder
import jp.snuffy.nativeVideoPlayerTest.R
import java.net.URLDecoder
import kotlin.math.max

class PlayerActivity : AppCompatActivity(), PlayerControlView.VisibilityListener, PlaybackPreparer {

    private var player : SimpleExoPlayer? = null
    private var mediaSource : MediaSource? = null
    private var adsLoader : AdsLoader? = null
    private var items: List<MediaItem>? = null

    private lateinit var dataSourceFactory : DataSource.Factory

    private var controllerView : ConstraintLayout? = null
    private var playerView : PlayerView? = null
    private var previewTimeBar : PreviewTimeBar? = null
    private var titleView : TextView? = null
    private var rateButton : Button? = null
    private var fullscreenButton : ImageButton? = null
    private var closeButton : ImageButton? = null
    private var lastSeenTrackGroupArray : TrackGroupArray? = null
    private var trackSelector : DefaultTrackSelector? = null
    private var trackSelectorParameters : DefaultTrackSelector.Parameters? = null

    private var startAutoPlay = true
    private var startWindow = C.INDEX_UNSET
    private var startPosition = C.TIME_UNSET
    private var playbackRate = PLAYBACK_RATE_10
    private var orientation: Int = Configuration.ORIENTATION_PORTRAIT

    companion object {
        // TAG
        private const val TAG = "PlayerActivity"

        // Saved instance state keys.
        private const val KEY_WINDOW = "window"
        private const val KEY_POSITION = "position"
        private const val KEY_AUTO_PLAY = "auto_play"
        private const val KEY_TRACK_SELECTOR_PARAMETERS = "track_selector_parameters"

        // Playback speed
        private const val PLAYBACK_RATE_08 = 0.8f
        private const val PLAYBACK_RATE_10 = 1.0f
        private const val PLAYBACK_RATE_15 = 1.5f

        @Suppress("DEPRECATED_IDENTITY_EQUALS")
        private fun isBehindLiveWindow(error :ExoPlaybackException) : Boolean {
            if (error.type !== ExoPlaybackException.TYPE_SOURCE) {
                return false
            }
            var cause: Throwable? = error.sourceException
            while (cause != null) {
                if (cause is BehindLiveWindowException) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.also {
            trackSelectorParameters = it.getParcelable(KEY_TRACK_SELECTOR_PARAMETERS)
            startAutoPlay = it.getBoolean(KEY_AUTO_PLAY)
            startWindow = it.getInt(KEY_WINDOW)
            startPosition = it.getLong(KEY_POSITION)
        } ?: run {
            val builder = DefaultTrackSelector.ParametersBuilder(this@PlayerActivity)
            trackSelectorParameters = builder.build()
            clearStartPosition()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        releasePlayer()
        releaseAdsLoader()
        clearStartPosition()
        setIntent(intent)
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onStart() {
        super.onStart()
        setContentView(R.layout.activity_player)

        controllerView = findViewById(R.id.controller_view)
        playerView = findViewById(R.id.player_view)
        playerView?.let {
            it.setControllerVisibilityListener(this)
            it.setErrorMessageProvider(PlayerErrorMessageProvider())
            it.requestFocus()
        }
        previewTimeBar = findViewById(R.id.exo_progress)
        titleView = findViewById(R.id.title_view)

        rateButton = findViewById(R.id.rate_change_button)
        rateButton?.setOnClickListener {
            var rate = playbackRate
            when (playbackRate) {
                PLAYBACK_RATE_08 -> {
                    rate = PLAYBACK_RATE_10
                }
                PLAYBACK_RATE_10 -> {
                    rate = PLAYBACK_RATE_15
                }
                PLAYBACK_RATE_15 -> {
                    rate = PLAYBACK_RATE_08
                }
            }
            setPlaybackSpeed(rate)
        }

        fullscreenButton = findViewById(R.id.fullscreen_button)
        fullscreenButton?.setOnClickListener {
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                fullscreenButton?.setImageResource(R.drawable.ic_fullscreen_exit_white)
                orientation = Configuration.ORIENTATION_LANDSCAPE
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                fullscreenButton?.setImageResource(R.drawable.ic_fullscreen_white)
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
        }

        closeButton = findViewById(R.id.close_button)
        closeButton?.setOnClickListener {
            finish()
        }

        dataSourceFactory = buildDataSourceFactory()

        if (Util.SDK_INT > 23) {
            initializePlayer()
            playerView?.apply {
                onResume()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // fullscreen
        window.decorView.apply {
            systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    )
        }

        if (Util.SDK_INT <= 23 || player == null) {
            initializePlayer()
            playerView?.apply {
                onResume()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (Util.SDK_INT <= 23) {
            playerView?.apply {
                onPause()
            }
            releasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Util.SDK_INT > 23) {
            playerView?.apply {
                onPause()
            }
            releasePlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAdsLoader()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializePlayer()
        } else {
            showToast(R.string.storage_permission_denied)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        updateTrackSelectorParameters()
        updateStartPosition()

        outState.apply {
            putBoolean(KEY_AUTO_PLAY, startAutoPlay)
            putInt(KEY_WINDOW, startWindow)
            putLong(KEY_POSITION, startPosition)
            putParcelable(KEY_TRACK_SELECTOR_PARAMETERS, trackSelectorParameters)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            fullscreenButton?.setImageResource(R.drawable.ic_fullscreen_white)
            orientation = Configuration.ORIENTATION_PORTRAIT
        } else {
            fullscreenButton?.setImageResource(R.drawable.ic_fullscreen_exit_white)
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
    }

    override fun onBackPressed() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && packageManager
                        .hasSystemFeature(
                                FEATURE_PICTURE_IN_PICTURE)){
            enterPIPMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        enterPIPMode()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?) {
        (super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig))

        if (!isInPictureInPictureMode) {
            playerView?.apply {
                useController = true
            }
        }
    }

    @Suppress("DEPRECATION")
    fun enterPIPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && packageManager.hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)) {
            playerView?.apply {
                useController = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = PictureInPictureParams.Builder()
                this.enterPictureInPictureMode(params.build())
            } else {
                this.enterPictureInPictureMode()
            }
        }
    }

    private fun initializePlayer() {
        mediaSource = createTopLevelMediaSource()

        if (player == null) {
            lastSeenTrackGroupArray = null
            trackSelector = DefaultTrackSelector(this)
            trackSelector?.let { trackSelector ->
                trackSelectorParameters?.let {
                    trackSelector.parameters = it
                }

                player = SimpleExoPlayer.Builder(applicationContext)
                        .setTrackSelector(trackSelector)
                        .build()
                        .also {
                            it.setAudioAttributes(AudioAttributes.DEFAULT, true)
                            setPlaybackSpeed(playbackRate)
                            it.playWhenReady = startAutoPlay
                            it.addListener(PlayerEventListener())
                            it.addAnalyticsListener(EventLogger(trackSelector))

                            playerView?.apply {
                                player = it
                                setPlaybackPreparer(this@PlayerActivity)
                            }

                            adsLoader?.apply {
                                setPlayer(it)
                            }

                            val haveStartPosition = startWindow != C.INDEX_UNSET
                            if (haveStartPosition) it.seekTo(startWindow, startPosition)

                            mediaSource?.let { mediaSource ->
                                it.prepare(mediaSource)
                            }
                        }
            }
        }
    }

    private fun createTopLevelMediaSource() : ConcatenatingMediaSource {
        intent.getStringExtra(MediaItem.MEDIA_ITEMS_EXTRA)?.let {
            GsonBuilder().create().apply {
                items = fromJson(it, Array<MediaItem>::class.java).toList()
            }
        }

        var concatMediaSource = ConcatenatingMediaSource()
        playerView?.let {
            items?.forEachIndexed { index, item ->
                val url = Uri.parse(URLDecoder.decode(item.source, "UTF-8"))
                when (Util.inferContentType(url)) {
                    C.TYPE_HLS -> {
                        val mediaSource = HlsMediaSource
                                .Factory(dataSourceFactory)
                                .setTag(index)
                                .createMediaSource(url)
                        concatMediaSource.addMediaSource(mediaSource)
                    }
                    C.TYPE_OTHER -> {
                        val mediaSource = ProgressiveMediaSource
                                .Factory(dataSourceFactory)
                                .setTag(index)
                                .createMediaSource(url)
                        concatMediaSource.addMediaSource(mediaSource)
                    }
                    else -> {
                        //do nothing.
                    }
                }
            }
        }

        return concatMediaSource
    }

    private fun releasePlayer() {
        player?.apply {
            updateTrackSelectorParameters()
            updateStartPosition()
            release()
            player = null
            mediaSource = null
            trackSelector = null
        }

        adsLoader?.apply {
            setPlayer(null)
        }
    }

    private fun releaseAdsLoader() {
        adsLoader?.also {
            it.release()
            adsLoader = null

            playerView?.apply {
                overlayFrameLayout?.apply {
                    removeAllViews()
                }
            }
        }
    }

    private fun updateTrackSelectorParameters() {
        trackSelector?.let {
            trackSelectorParameters = it.parameters
        }
    }

    private fun updateStartPosition() {
        player?.let {
            startAutoPlay = it.playWhenReady
            startWindow = it.currentWindowIndex
            startPosition = max(0, it.contentPosition)

        }
    }

    private fun clearStartPosition() {
        startAutoPlay = true
        startWindow = C.INDEX_UNSET
        startPosition = C.TIME_UNSET
    }

    private fun setPlaybackSpeed(rate : Float) {
        if (rate < 0.5 || rate > 2.0) {
            Log.w(TAG, "playback speed is invalid, speed = [" + rate + "]");
            return;
        }
        player?.let {
            val playbackParameters = it.playbackParameters;
            if (playbackParameters.speed != rate) {
                it.setPlaybackParameters(PlaybackParameters(rate));
                playbackRate = rate
            } else {
                Log.d(TAG, "playback speed is not changed!");
            }
        }
        rateButton?.text = String.format("x%.1f", rate)
    }

    private fun buildDataSourceFactory() : DataSource.Factory {
        val userAgent = Util.getUserAgent(applicationContext, applicationInfo.loadLabel(packageManager).toString())
        return DefaultDataSourceFactory(applicationContext,
                                        Util.getUserAgent(applicationContext, userAgent))
    }

    private fun getMimeType(url: String): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        extension?.let {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
        return null
    }

    private fun showControls() {
        controllerView?.apply {
            this.visibility = View.VISIBLE
        }
    }

    private fun showToast(messageId: Int) {
        showToast(getString(messageId))
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private inner class PlayerEventListener : Player.EventListener {
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                showControls()
            }
        }

        override fun onPlayerError(error: ExoPlaybackException) {
            if (isBehindLiveWindow(error)) {
                clearStartPosition()
                initializePlayer()
            } else {
                showControls()
            }
        }

        override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {
            if (trackGroups != lastSeenTrackGroupArray) {
                player?.let {  player ->
                    items?.let {items ->
                        val item = items[player.currentTag as Int]
                        titleView?.text = item.title
                        item.source?.let {
                            val mimeType = getMimeType(it)
                            if (mimeType != null && mimeType.startsWith("video")) {
                                titleView?.visibility = View.INVISIBLE
                            } else {
                                titleView?.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
            lastSeenTrackGroupArray = trackGroups
        }
    }

    private inner class PlayerErrorMessageProvider : ErrorMessageProvider<ExoPlaybackException> {
        override fun getErrorMessage(throwable: ExoPlaybackException): Pair<Int, String> {
            var errorString = getString(R.string.error_generic)

            if (throwable.type == ExoPlaybackException.TYPE_RENDERER) {
                val cause = throwable.rendererException
                if (cause is MediaCodecRenderer.DecoderInitializationException) {
                    val decoderInitializationException = cause
                    decoderInitializationException.codecInfo?.also {
                        if (decoderInitializationException.cause is MediaCodecUtil.DecoderQueryException) {
                            errorString = getString(R.string.error_querying_decoders)
                        } else if (decoderInitializationException.secureDecoderRequired) {
                            errorString = getString(R.string.error_no_secure_decoder, decoderInitializationException.mimeType)
                        } else {
                            errorString = getString(R.string.error_no_decoder, decoderInitializationException.mimeType)
                        }
                    } ?: run {
                        errorString = getString(R.string.error_instantiating_decoder, decoderInitializationException.codecInfo?.name)
                    }
                }
            }

            return Pair.create(0, errorString)
        }
    }

    // MARK: - PlayerControlView.VisibilityListener

    override fun onVisibilityChange(visibility: Int) {
        controllerView?.apply {
            this.visibility = visibility
        }
    }

    // MARK: - PlaybackPreparer

    override fun preparePlayback() {
        player?.retry()
    }
}