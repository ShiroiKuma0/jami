/*
 *  Copyright (C) 2026 白い熊 (ShiroiKuma0)
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
package cx.ring.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cx.ring.application.JamiApplication

/** The one wake owed to a noise push dropped inside the cooldown. See [JamiApplication.onDeferredWake]. */
class DeferredWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? JamiApplication)?.onDeferredWake()
    }
}
