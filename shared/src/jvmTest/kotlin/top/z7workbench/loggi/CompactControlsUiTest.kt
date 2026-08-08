package top.z7workbench.loggi

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import top.z7workbench.loggi.ui.CompactButton
import top.z7workbench.loggi.ui.CompactDropdownMenu
import top.z7workbench.loggi.ui.CompactMenuItem
import top.z7workbench.loggi.ui.CompactNumberSpinner

/** UI tests for the settings number spinner (type-to-edit + ▲▼ nudge). */
@OptIn(ExperimentalTestApi::class)
class CompactControlsUiTest {
    /**
     * A menu anchored within 48 dp of the window top must still open flush
     * below its anchor — Material3's DropdownMenu jumps to `MenuVerticalMargin`
     * (48 dp) there, which visibly detached the toolbar's layout menu from its
     * button; CompactDropdownMenu must not.
     */
    @Test
    fun dropdownOpensFlushBelowAnchorAtWindowTop() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.padding(top = 3.dp, start = 6.dp)) {
                    Box {
                        CompactButton("Layout  ▾", onClick = {}, modifier = Modifier.testTag("btn"))
                        CompactDropdownMenu(
                            expanded = true,
                            onDismissRequest = {},
                            modifier = Modifier.testTag("menu"),
                        ) {
                            CompactMenuItem(text = "item", onClick = {})
                        }
                    }
                }
            }
        }
        awaitIdle()
        val btn = onNodeWithTag("btn").fetchSemanticsNode().boundsInRoot
        val menu = onNodeWithTag("menu").fetchSemanticsNode().boundsInRoot
        assertEquals(btn.bottom, menu.top, 1f, "menu top must sit at the anchor's bottom edge")
        assertEquals(btn.left, menu.left, 1f, "menu left must sit at the anchor's left edge")
    }
    @Test
    fun typedValueCommitsOnImeDone() = runComposeUiTest {
        var value = 13f
        setContent { MaterialTheme { CompactNumberSpinner(value, { value = it }, 8f..32f, 1f, 0) } }
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("20")
        onNode(hasSetTextAction()).performImeAction()
        runOnIdle { assertEquals(20f, value) }
    }

    @Test
    fun spinnerButtonsNudgeAndClamp() = runComposeUiTest {
        var value = 32f
        setContent { MaterialTheme { CompactNumberSpinner(value, { value = it }, 8f..32f, 1f, 0) } }
        onNodeWithContentDescription("Increase").performClick()
        runOnIdle { assertEquals(32f, value) } // clamped at the range end
        onNodeWithContentDescription("Decrease").performClick()
        runOnIdle { assertEquals(31f, value) }
    }

    @Test
    fun invalidInputRevertsToCurrentValue() = runComposeUiTest {
        var value = 13f
        setContent { MaterialTheme { CompactNumberSpinner(value, { value = it }, 8f..32f, 1f, 0) } }
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("abc")
        onNode(hasSetTextAction()).performImeAction()
        runOnIdle { assertEquals(13f, value) }
    }

    @Test
    fun decimalsRoundOnCommit() = runComposeUiTest {
        var value = 1.2f
        setContent { MaterialTheme { CompactNumberSpinner(value, { value = it }, 1f..2f, 0.05f, 2) } }
        onNodeWithContentDescription("Increase").performClick()
        runOnIdle { assertEquals(1.25f, value) }
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("1.6666")
        onNode(hasSetTextAction()).performImeAction()
        runOnIdle { assertEquals(1.67f, value) }
    }
}
