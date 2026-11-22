package androidx.appcompat.view.menu

import android.annotation.SuppressLint

/** 白い熊 fork: reads the menu a row belongs to — the field is package-visible only. */
@SuppressLint("RestrictedApi")
object SkMenuAccess {
    fun menuOf(item: MenuItemImpl?): MenuBuilder? = try {
        item?.mMenu
    } catch (ignored: Throwable) {
        null
    }
}
