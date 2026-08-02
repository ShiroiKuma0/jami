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
package cx.ring.viewholders

import android.view.animation.AlphaAnimation
import android.view.animation.DecelerateInterpolator
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import cx.ring.databinding.ItemContactBinding
import cx.ring.utils.DeviceUtils
import cx.ring.views.AvatarDrawable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import net.jami.model.*
import net.jami.services.ConversationFacade
import net.jami.smartlist.ConversationItemViewModel

class ContactPickerViewHolder(b: ItemContactBinding, private val conversationFacade: ConversationFacade) :
    RecyclerView.ViewHolder(b.root) {
    private val binding: ItemContactBinding = b
    private var currentUri: Uri? = null
    private val compositeDisposable = CompositeDisposable()

    private fun fadeIn() = AlphaAnimation(0f, 1f).apply {
        interpolator = DecelerateInterpolator()
        duration = itemView.context.resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
    }

    fun bind(clickListener: ContactPickerListeners, conversation: ConversationItemViewModel) {
        compositeDisposable.clear()
        if (conversation.uri != currentUri) {
            currentUri = conversation.uri
            binding.quickCall.isVisible = false
            binding.photo.setAvatar(null)
        }

        itemView.setOnClickListener { clickListener.onItemClick(conversation) }
        itemView.setOnLongClickListener {
            clickListener.onItemLongClick(conversation)
            true
        }

        binding.displayName.text = conversation.title
        val fade = binding.photo.setAvatar(AvatarDrawable.Builder()
            .withViewModel(conversation)
            .withCircleCrop(true)
            .build(binding.photo.context))
        if (fade)
            binding.photo.startAnimation(fadeIn())

        // The list itself comes from a presence-less snapshot, so the avatar above carries no dot.
        // Subscribe to the live model — the same one the smart list uses — purely for the dot, so
        // the picker agrees with the chat list behind it instead of painting everyone offline.
        // Check state stays with the row's own model: the live one is a fresh, unchecked instance.
        compositeDisposable.add(
            conversationFacade.observeConversation(conversation.accountId, conversation.uri, true)
                .onErrorComplete()
                .observeOn(DeviceUtils.uiScheduler)
                .subscribe { live ->
                    binding.photo.setAvatar(AvatarDrawable.Builder()
                        .withViewModel(live)
                        .withCircleCrop(true)
                        .withCheck(conversation.isChecked)
                        .build(binding.photo.context))
                })
    }

    fun unbind() {
        compositeDisposable.clear()
        binding.photo.setAvatar(null)
    }

    interface ContactPickerListeners {
        fun onItemClick(item: ConversationItemViewModel)
        fun onItemLongClick(item: ConversationItemViewModel)
    }
}