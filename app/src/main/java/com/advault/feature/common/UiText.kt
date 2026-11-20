package com.advault.feature.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Text produced by a ViewModel without holding a Context. Resolved in composition. */
sealed interface UiText {
    data class Plain(val value: String) : UiText
    class Res(@param:StringRes val id: Int, vararg val args: Any) : UiText {
        override fun equals(other: Any?): Boolean = other is Res && other.id == id && other.args.contentEquals(args)
        override fun hashCode(): Int = 31 * id + args.contentHashCode()
        override fun toString(): String = "Res($id, ${args.toList()})"
    }
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Plain -> value
    is UiText.Res -> stringResource(id, *args)
}

fun UiText.resolve(context: android.content.Context): String = when (this) {
    is UiText.Plain -> value
    is UiText.Res -> context.getString(id, *args)
}
