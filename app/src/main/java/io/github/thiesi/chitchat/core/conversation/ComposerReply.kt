package io.github.thiesi.chitchat.core.conversation

/** Draft metadata only, never a cached incoming body. Identity guards cancellation of a successor. */
class ComposerReply(val messageId: Long) {
    init { require(messageId > 0) }
}
