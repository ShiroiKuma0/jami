/*
 *  Copyright (C) 2004-2025 Savoir-faire Linux Inc.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package cx.ring.client

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.Toast
import cx.ring.utils.Flash
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.bottomappbar.BottomAppBar
import com.jsibbold.zoomage.ZoomageView
import cx.ring.R
import cx.ring.utils.AndroidFileUtils
import cx.ring.utils.ContentUri.getUriForFile
import cx.ring.utils.DeviceUtils
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.disposables.CompositeDisposable
import net.jami.model.Uri as JamiUri
import net.jami.model.interaction.DataTransfer
import net.jami.services.AccountService
import net.jami.services.DeviceRuntimeService
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class MediaViewerFragment : Fragment() {
    @Inject
    lateinit var accountService: AccountService

    @Inject
    lateinit var deviceRuntimeService: DeviceRuntimeService

    private val disposable = CompositeDisposable()

    // Current item the bottom-bar actions (share/save/open) operate on. In single mode this
    // stays the tapped URI; in swipe mode it tracks the visible page.
    private var mUri: Uri? = null

    // Single-mode video playback (gallery / TV / no swipe context). Unchanged from upstream.
    private var videoView: android.widget.VideoView? = null
    private var mediaController: android.widget.MediaController? = null
    private var previewWidth = 0
    private var previewHeight = 0

    // Swipe (pager) mode.
    private var pager: ViewPager2? = null
    private var pagerAdapter: MediaPagerAdapter? = null
    private var isVideoMode = false
    private var transitionStarted = false
    private var tappedKey: String? = null
    private var currentKey: String? = null
    private var currentItems: List<MediaItem> = emptyList()
    private val attachedVideoHolders = mutableSetOf<VideoPageViewHolder>()
    private val transitionName: String
        get() = if (isVideoMode) "video" else "picture"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = requireActivity().intent
        mUri = intent.data
        postponeEnterTransition()
        // Get thumbnail dimensions from intent
        previewWidth = intent.getIntExtra("video_width", 0)
        previewHeight = intent.getIntExtra("video_height", 0)
        tappedKey = intent.getStringExtra(EXTRA_SWIPE_KEY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_media_viewer, container, false)
        val bottomAppBar = view.findViewById<BottomAppBar>(R.id.bottomAppBar)
        val shareButton = view.findViewById<Button>(R.id.shareBtn)
        val uri = mUri ?: return view
        val mimeType = AndroidFileUtils.getMimeType(requireContext().contentResolver, uri)

        val intent = requireActivity().intent
        val swipeAccount = intent.getStringExtra(EXTRA_SWIPE_ACCOUNT)
        val swipeConversation = intent.getStringExtra(EXTRA_SWIPE_CONVERSATION)
        if (swipeAccount != null && swipeConversation != null) {
            setupSwipe(view, swipeAccount, swipeConversation, mimeType)
        } else {
            setupSingle(view, uri, mimeType)
        }

        bottomAppBar.setOnMenuItemClickListener {
            val u = mUri ?: return@setOnMenuItemClickListener true
            when (it.itemId) {
                R.id.conv_action_share -> AndroidFileUtils.shareFile(requireContext(), u)
                R.id.conv_action_download -> startSaveFile(u)
                R.id.conv_action_open -> openFile(u)
            }
            true
        }

        shareButton.setOnClickListener {
            mUri?.let { AndroidFileUtils.shareFile(requireContext(), it) }
        }

        return view
    }

    // -- Single item (upstream behaviour: gallery, TV, videos with no swipe context) --

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSingle(view: View, uri: Uri, mimeType: String?) {
        val imageView = view.findViewById<View>(R.id.image)
        videoView = view.findViewById(R.id.video_view)
        val edgeThreshold = ViewConfiguration.get(requireContext()).scaledEdgeSlop

        if (mimeType?.startsWith("video/") == true) {
            if (previewWidth > 0 && previewHeight > 0) {
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                val aspectRatio = previewWidth.toFloat() / previewHeight
                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val (w, h) = if (isLandscape) {
                    val height = screenHeight
                    val width = (height * aspectRatio).toInt().coerceAtMost(screenWidth)
                    width to height
                } else {
                    val width = screenWidth
                    val height = (width / aspectRatio).toInt().coerceAtMost(screenHeight)
                    width to height
                }

                videoView?.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                    gravity = Gravity.CENTER
                }
            }

            imageView.visibility = View.GONE
            videoView?.visibility = View.VISIBLE
            videoView?.apply {
                mediaController = android.widget.MediaController(requireContext()).apply {
                    setAnchorView(this@apply)
                }
                setMediaController(mediaController)
                setVideoURI(uri)
                setOnPreparedListener { mp ->
                    val videoWidth = mp.videoWidth
                    val videoHeight = mp.videoHeight
                    val thumbRatio = calculateAspectRatio(previewWidth, previewHeight)
                    val videoRatio = calculateAspectRatio(videoWidth, videoHeight)

                    if (abs(thumbRatio - videoRatio) > 0.01f) {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val screenHeight = resources.displayMetrics.heightPixels
                        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                        val (w, h) = if (isLandscape) {
                            val height = screenHeight
                            val width = (height * videoRatio).toInt().coerceAtMost(screenWidth)
                            width to height
                        } else {
                            val width = screenWidth
                            val height = (width / videoRatio).toInt().coerceAtMost(screenHeight)
                            width to height
                        }
                        layoutParams = FrameLayout.LayoutParams(w, h).apply {
                            gravity = Gravity.CENTER
                        }
                    }

                    start()

                    view.findViewById<ViewGroup>(R.id.video_container)?.viewTreeObserver
                        ?.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                view.viewTreeObserver.removeOnPreDrawListener(this)
                                startTransitionOnce()
                                return true
                            }
                        })
                }
            }
        } else {
            videoView?.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            Glide.with(this).load(uri).into(imageView as android.widget.ImageView)
            startTransitionOnce()
        }

        videoView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val screenWidth = resources.displayMetrics.widthPixels
                if (event.x < edgeThreshold || event.x > screenWidth - edgeThreshold) {
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    // -- Swipe mode: a pager over every picture (or every video) of the tapped item's direction --

    private fun setupSwipe(view: View, accountId: String, conversationId: String, mimeType: String?) {
        isVideoMode = mimeType?.startsWith("video/") == true
        // Pager replaces the single image/video surfaces. Clear their XML transition names too,
        // so only the pager's hero page owns the shared-element name during the open animation.
        view.findViewById<View>(R.id.image).apply { visibility = View.GONE; transitionName = null }
        view.findViewById<View>(R.id.video_container).apply { visibility = View.GONE; transitionName = null }
        val vp = view.findViewById<ViewPager2>(R.id.media_pager).apply { visibility = View.VISIBLE }
        pager = vp
        currentKey = tappedKey

        val adapter = MediaPagerAdapter()
        pagerAdapter = adapter
        vp.adapter = adapter

        // Show the tapped item immediately so the open is instant and the shared-element
        // transition lands; the rest of the set is filled in once the search returns.
        currentItems = listOf(MediaItem(mUri!!, tappedKey))
        adapter.submitList(currentItems)

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val item = currentItems.getOrNull(position) ?: return
                mUri = item.uri
                currentKey = item.key
                if (isVideoMode) {
                    attachedVideoHolders.forEach { h ->
                        if (h.bindingAdapterPosition == position) h.play() else h.pauseReset()
                    }
                }
            }
        })

        // Guarantee the postponed enter transition is released once the pager is drawn.
        vp.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                vp.viewTreeObserver.removeOnPreDrawListener(this)
                startTransitionOnce()
                return true
            }
        })

        val incoming = requireActivity().intent.getBooleanExtra(EXTRA_SWIPE_INCOMING, true)
        val convUri = JamiUri.fromString(conversationId)
        val collected = ArrayList<DataTransfer>()
        disposable.add(
            accountService.searchConversation(accountId, convUri, type = "application/data-transfer+json")
                .observeOn(DeviceUtils.uiScheduler)
                .subscribe({ result ->
                    for (i in result.results) if (i is DataTransfer) collected.add(i)
                    val items = collected.asSequence()
                        .filter {
                            it.isComplete && it.isIncoming == incoming &&
                                (if (isVideoMode) it.isVideo else it.isPicture)
                        }
                        .distinctBy { it.messageId ?: it.fileId ?: it.storagePath }
                        .sortedBy { it.timestamp }
                        .mapNotNull { buildMediaItem(it) }
                        .toList()
                    val anchorKey = currentKey ?: tappedKey
                    val hasAnchor = items.any { (it.key != null && it.key == anchorKey) || it.uri == mUri }
                    if (items.isNotEmpty() && hasAnchor) submitItems(items, anchorKey)
                }, { /* keep the single tapped item on search failure */ })
        )
    }

    private fun submitItems(items: List<MediaItem>, anchorKey: String?) {
        currentItems = items
        pagerAdapter?.submitList(items) {
            var idx = items.indexOfFirst { it.key != null && it.key == anchorKey }
            if (idx < 0) idx = items.indexOfFirst { it.uri == mUri }
            if (idx >= 0) {
                pager?.setCurrentItem(idx, false)
                mUri = items[idx].uri
                currentKey = items[idx].key
            }
        }
    }

    private fun buildMediaItem(dt: DataTransfer): MediaItem? = try {
        val file = deviceRuntimeService.getConversationPath(dt)
        if (!file.exists()) null
        else MediaItem(getUriForFile(requireContext(), file, dt.body), dt.messageId ?: dt.fileId)
    } catch (e: Exception) {
        null
    }

    private fun startTransitionOnce() {
        if (transitionStarted) return
        transitionStarted = true
        startPostponedEnterTransition()
    }

    private fun sizeVideoView(v: android.widget.VideoView, videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val ratio = videoWidth.toFloat() / videoHeight
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val (w, h) = if (isLandscape) {
            val height = screenHeight
            val width = (height * ratio).toInt().coerceAtMost(screenWidth)
            width to height
        } else {
            val width = screenWidth
            val height = (width / ratio).toInt().coerceAtMost(screenHeight)
            width to height
        }
        v.layoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER }
    }

    private fun calculateAspectRatio(width: Int, height: Int): Float {
        return if (height != 0) width.toFloat() / height else 1f
    }

    override fun onPause() {
        videoView?.pause()
        if (isVideoMode) attachedVideoHolders.forEach { it.pauseOnly() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (isVideoMode) {
            val pos = pager?.currentItem ?: 0
            attachedVideoHolders.firstOrNull { it.bindingAdapterPosition == pos }?.play()
        } else {
            mUri?.let {
                videoView?.apply {
                    if (!isPlaying) start()
                }
            }
        }
    }

    override fun onDestroyView() {
        disposable.clear()
        attachedVideoHolders.forEach { it.release() }
        attachedVideoHolders.clear()
        pager?.adapter = null
        pager = null
        pagerAdapter = null
        super.onDestroyView()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_SAVE_FILE) {
            data?.data?.let { uri ->
                val source = mUri ?: return
                AndroidFileUtils.copyUri(requireContext().contentResolver, source, uri)
                    .observeOn(DeviceUtils.uiScheduler)
                    .subscribe({ Flash.show(context, R.string.file_saved_successfully, Toast.LENGTH_SHORT) })
                    { Flash.show(context, R.string.generic_error, Toast.LENGTH_SHORT) }
            }
        } else
            super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startSaveFile(uri: Uri) {
        try {
            val name = uri.getQueryParameter("displayName") ?: ""
            val downloadFileIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = requireContext().contentResolver.getType(uri.buildUpon().appendPath(name).build())
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, name)
            }
            startActivityForResult(downloadFileIntent, REQUEST_CODE_SAVE_FILE)
        } catch (e: Exception) {
            //Log.i(TAG, "No app detected for saving files.")
        }
    }

    private fun openFile(uri: Uri) {
        val name = uri.getQueryParameter("displayName") ?: ""
        AndroidFileUtils.openFile(requireContext(), uri, name)
    }

    data class MediaItem(val uri: Uri, val key: String?)

    private inner class MediaPagerAdapter :
        ListAdapter<MediaItem, RecyclerView.ViewHolder>(DIFF) {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long {
            val item = getItem(position)
            return (item.key ?: item.uri.toString()).hashCode().toLong()
        }

        override fun getItemViewType(position: Int): Int = if (isVideoMode) VIEW_TYPE_VIDEO else VIEW_TYPE_IMAGE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_VIDEO)
                VideoPageViewHolder(inflater.inflate(R.layout.item_media_pager_video, parent, false))
            else
                ImagePageViewHolder(inflater.inflate(R.layout.item_media_pager_image, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = getItem(position)
            when (holder) {
                is ImagePageViewHolder -> holder.bind(item)
                is VideoPageViewHolder -> holder.bind(item)
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            if (holder is VideoPageViewHolder) attachedVideoHolders.add(holder)
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            if (holder is VideoPageViewHolder) {
                attachedVideoHolders.remove(holder)
                holder.release()
            }
        }
    }

    private inner class ImagePageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ZoomageView = view as ZoomageView

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: MediaItem) {
            // Only the tapped item carries the transition name, so the open animation is unambiguous.
            image.transitionName = if (item.key != null && item.key == tappedKey) transitionName else null
            Glide.with(this@MediaViewerFragment).load(item.uri).into(image)
            // Let the pager swipe when the image is at rest, but hand horizontal drags to the
            // image (panning) once it has been zoomed in.
            image.setOnTouchListener { _, _ ->
                pager?.isUserInputEnabled = image.currentScaleFactor <= 1.001f
                false
            }
        }
    }

    private inner class VideoPageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val container: FrameLayout = view.findViewById(R.id.page_video_container)
        private val video: android.widget.VideoView = view.findViewById(R.id.page_video)
        private var controller: android.widget.MediaController? = null

        fun bind(item: MediaItem) {
            container.transitionName = if (item.key != null && item.key == tappedKey) transitionName else null
            controller = android.widget.MediaController(requireContext()).apply { setAnchorView(video) }
            video.setMediaController(controller)
            video.setVideoURI(item.uri)
            video.setOnPreparedListener { mp ->
                sizeVideoView(video, mp.videoWidth, mp.videoHeight)
                if (bindingAdapterPosition == (pager?.currentItem ?: 0)) video.start()
                else video.seekTo(1)
                startTransitionOnce()
            }
        }

        fun play() {
            if (!video.isPlaying) video.start()
        }

        fun pauseReset() {
            if (video.isPlaying) video.pause()
            video.seekTo(0)
        }

        fun pauseOnly() {
            if (video.isPlaying) video.pause()
        }

        fun release() {
            video.stopPlayback()
        }
    }

    companion object {
        private const val REQUEST_CODE_SAVE_FILE = 1003
        private const val VIEW_TYPE_IMAGE = 0
        private const val VIEW_TYPE_VIDEO = 1

        const val EXTRA_SWIPE_ACCOUNT = "swipe_account"
        const val EXTRA_SWIPE_CONVERSATION = "swipe_conversation"
        const val EXTRA_SWIPE_INCOMING = "swipe_incoming"
        const val EXTRA_SWIPE_KEY = "swipe_key"

        private val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(a: MediaItem, b: MediaItem): Boolean =
                if (a.key != null && b.key != null) a.key == b.key else a.uri == b.uri

            override fun areContentsTheSame(a: MediaItem, b: MediaItem): Boolean = a.uri == b.uri
        }
    }
}
