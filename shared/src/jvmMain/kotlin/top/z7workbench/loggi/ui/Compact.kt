package top.z7workbench.loggi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.pow
import kotlin.math.roundToInt

/** Fixed height shared by one-line inputs and buttons so rows align. */
val CompactControlHeight = 26.dp

/**
 * Compact desktop controls. Stock Material 3 padding is tuned for touch
 * (56 dp text fields, ~48 dp menu items, 40 dp buttons) and reads as bloated
 * on desktop — these replacements implement the M8.5 compact-density
 * boundary condition (docs/PLAN.md §1.7).
 */
@Composable
fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isError: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor = when {
        isError -> scheme.error
        focused -> scheme.primary
        else -> scheme.outline
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        // Inherit the themed UI font family; only size/color are overridden.
        textStyle = LocalTextStyle.current.merge(TextStyle(fontSize = 13.sp, color = scheme.onSurface)),
        cursorBrush = SolidColor(scheme.primary),
        interactionSource = interactionSource,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CompactControlHeight)
                    .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Box {
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    }
                    inner()
                }
            }
        },
    )
}

/** Outlined-button look at desktop density (same height as the text fields). */
@Composable
fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(CompactControlHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .border(
                1.dp,
                if (selected) scheme.secondary else scheme.outline,
                RoundedCornerShape(4.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            maxLines = 1,
            color = when {
                !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
                selected -> scheme.onSecondaryContainer
                else -> scheme.onSurface
            },
        )
    }
}

/** Menu item at desktop density (~26 dp tall instead of ~48 dp). */
@Composable
fun CompactMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            maxLines = 1,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/** Compact menu row with custom content (e.g. a color swatch). */
@Composable
fun CompactMenuCustom(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        content()
    }
}

/**
 * Anchored dropdown menu with the compact item density. Material3's
 * `DropdownMenu` refuses to place a popup within 48 dp of the window edges
 * (`MenuVerticalMargin`), so menus anchored near the window top (toolbar,
 * tab bar, right-click on the first log rows) jump away from their anchor.
 * This replacement always opens with its top edge at the anchor's bottom
 * edge (start-aligned), flipping above the anchor only when the space below
 * does not fit, and keeping a small 4 dp margin to the window edges.
 */
@Composable
fun CompactDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val provider = remember(offset, density) { AnchorMenuPositionProvider(offset, density) }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = modifier,
            shape = MenuDefaults.shape,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
        ) {
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                content = content,
            )
        }
    }
}

/** Exact anchor-below positioning for [CompactDropdownMenu] (see its doc). */
private class AnchorMenuPositionProvider(
    private val contentOffset: DpOffset,
    private val density: Density,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = with(density) { 4.dp.roundToPx() }
        val offsetX = with(density) { contentOffset.x.roundToPx() }
        val offsetY = with(density) { contentOffset.y.roundToPx() }
        val maxX = (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            (anchorBounds.left + offsetX).coerceIn(margin, maxX)
        } else {
            (anchorBounds.right - popupContentSize.width - offsetX).coerceIn(margin, maxX)
        }
        val below = anchorBounds.bottom + offsetY
        val above = anchorBounds.top - offsetY - popupContentSize.height
        val maxY = (windowSize.height - margin - popupContentSize.height).coerceAtLeast(margin)
        val y = when {
            below + popupContentSize.height <= windowSize.height - margin -> below
            above >= margin -> above
            else -> maxY
        }
        return IntOffset(x, y)
    }
}

/**
 * Numeric field with spinner buttons: type a value and commit with Enter or
 * focus loss, or nudge with ▲▼. Values are clamped to [range] and rounded to
 * [decimals] places. Used by settings for font size, line spacing, tab stop.
 */
@Composable
fun CompactNumberSpinner(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    decimals: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val factor = 10f.pow(decimals)

    fun fmt(v: Float) = "%.${decimals}f".format(v)
    fun round(v: Float) = (v * factor).roundToInt() / factor

    // Non-null while the field is being edited; the display falls back to the
    // committed value, so external changes (e.g. Ctrl+± font size) stay visible.
    var editing by remember { mutableStateOf<String?>(null) }
    val shown = editing ?: fmt(value)

    fun commit() {
        val parsed = editing?.trim()?.toFloatOrNull()
        editing = null
        if (parsed != null) onValueChange(round(parsed.coerceIn(range)))
    }

    fun nudge(direction: Float) {
        val base = editing?.trim()?.toFloatOrNull() ?: value
        editing = null
        onValueChange(round(base + direction * step).coerceIn(range))
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = shown,
            onValueChange = { editing = it },
            singleLine = true,
            // Inherit the themed UI font family; only size/color are overridden.
            textStyle = LocalTextStyle.current.merge(TextStyle(fontSize = 12.sp, color = scheme.onSurface)),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(58.dp)
                .onFocusChanged { st ->
                    if (st.isFocused) {
                        if (editing == null) editing = fmt(value)
                    } else {
                        commit()
                    }
                },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .height(CompactControlHeight)
                        .border(
                            1.dp,
                            if (editing != null) scheme.primary else scheme.outline,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { inner() }
            },
        )
        Column(Modifier.padding(start = 2.dp)) {
            SpinArrow("▲") { nudge(1f) }
            SpinArrow("▼", Modifier.padding(top = 1.dp)) { nudge(-1f) }
        }
    }
}

@Composable
private fun SpinArrow(glyph: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .width(16.dp)
            .height(10.dp)
            .clip(RoundedCornerShape(2.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 7.sp, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}
