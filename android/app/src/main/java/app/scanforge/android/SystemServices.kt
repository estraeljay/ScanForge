package app.scanforge.android

import android.content.Context

/** Non-null system service lookup (Context.getSystemService(Class) is declared @Nullable). */
inline fun <reified T : Any> Context.systemService(): T =
    requireNotNull(getSystemService(T::class.java)) { "System service ${T::class.java.simpleName} unavailable" }
