package io.github.thiesi.chitchat.core.theme

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Enforces the theme's ownership boundary, not individual implementation constants. */
class ThemeSourceContractTest {
    private fun sources(): List<File> {
        val base=listOf(File("src/main/java"),File("app/src/main/java")).single { it.isDirectory }
        return base.walkTopDown().filter { it.isFile && it.extension=="kt" }.toList()
    }
    @Test fun literalColorsAndIdentityShapesStayOwnedByTheTheme() {
        for(file in sources().filterNot { "/core/theme/" in it.invariantSeparatorsPath }) {
            val text=file.readText()
            assertFalse(file.path,Regex("Color\\s*\\(\\s*0x").containsMatchIn(text))
            assertFalse(file.path,Regex("RoundedCornerShape\\s*\\(\\s*\\d").containsMatchIn(text))
        }
    }
    @Test fun filledButtonsAndBadgesUseTheSharedThemeWrappers() {
        for(file in sources()) {
            val text=file.readText()
            if(file.name!="CountBadge.kt") assertFalse(file.path,Regex("\\bBadge\\s*[({]").containsMatchIn(text))
            if(file.name!="PrimaryButton.kt") assertFalse(file.path,Regex("\\bButton\\s*\\(").containsMatchIn(text))
        }
    }
    @Test fun systemAppearanceHasOneResolverRatherThanReadingAnAppOverride() {
        for(file in sources()) assertFalse(file.path,file.readText().contains("isSystemInDarkTheme("))
    }
}
