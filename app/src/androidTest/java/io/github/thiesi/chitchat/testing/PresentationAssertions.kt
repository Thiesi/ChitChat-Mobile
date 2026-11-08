package io.github.thiesi.chitchat.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

/** The actual expanded touch bounds, not the icon or painted rectangle. No UI action. */
internal fun SemanticsNodeInteraction.assertMinTouchTarget(density: Density,minimum: Dp=48.dp): SemanticsNodeInteraction {
    val bounds=fetchSemanticsNode().touchBoundsInRoot
    val pixels=with(density) { minimum.toPx() }
    assertTrue("Touch width below the accessibility floor",bounds.width+0.5f>=pixels)
    assertTrue("Touch height below the accessibility floor",bounds.height+0.5f>=pixels)
    return this
}
