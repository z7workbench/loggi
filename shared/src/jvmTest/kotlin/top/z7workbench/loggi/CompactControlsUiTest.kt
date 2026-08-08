package top.z7workbench.loggi

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import top.z7workbench.loggi.ui.CompactNumberSpinner

/** UI tests for the settings number spinner (type-to-edit + ▲▼ nudge). */
@OptIn(ExperimentalTestApi::class)
class CompactControlsUiTest {
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
        onNodeWithText("▲").performClick()
        runOnIdle { assertEquals(32f, value) } // clamped at the range end
        onNodeWithText("▼").performClick()
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
        onNodeWithText("▲").performClick()
        runOnIdle { assertEquals(1.25f, value) }
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("1.6666")
        onNode(hasSetTextAction()).performImeAction()
        runOnIdle { assertEquals(1.67f, value) }
    }
}
