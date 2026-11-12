package me.notific.client

/**
 * What the swipe on an inbox row does (docs/27, D9): the action follows the
 * kind of notification, not the side. Pure, so the rule is tested apart from
 * the UI and is the same on the phone and on the watch.
 */
public object SwipeRule {
    /** The kind of a row, as far as the swipe goes. */
    public enum class Kind {
        /** A plain notification: nothing asked of the person but to read it. */
        Plain,

        /** A decision or a question (a question set): only opened, never answered by a swipe. */
        Decision,

        /** Waiting for the person's "got it" (a critical alert or a message from a person). */
        Ack,
    }

    /** What the swipe does. */
    public enum class Intent {
        /** Read for real: the whole text was on the card, so `opened` is the truth (D1 still holds). */
        Read,

        /** Already read: back to unread (only on this device's list, as before). */
        Unread,

        /** Opens it: its text did not fit, there is more behind it, or it is a decision. */
        Open,

        /** Gives the "got it" (after the undo window). */
        Ack,
    }

    /**
     * The kind of [n]. [waitsForAck]: still waiting for the person's "got it"
     * (see [Notification.asksForAck] and the app's own pending state).
     */
    public fun kindOf(n: Notification, waitsForAck: Boolean): Kind = when {
        n.questionSetId != null -> Kind.Decision
        waitsForAck -> Kind.Ack
        else -> Kind.Plain
    }

    /**
     * Whether [n] has something the row does not show: a card with blocks
     * other than the text the row already shows (a table, an image, buttons…),
     * rich HTML, or a card text the row does not show (a message's full text).
     */
    public fun hasMore(n: Notification): Boolean {
        val content = n.content ?: return false
        if (content.rich != null) return true
        val card = content.card ?: return false
        val shown = setOf(n.headline.norm(), n.subtitle.norm(), n.title.orEmpty().norm(), n.summary.orEmpty().norm())
        // Um bloco de texto que já aparece dentro do título ou do resumo (o
        // resumo "Título: texto" com o mesmo texto no card) não é conteúdo a mais.
        return card.blocks.any { b -> b !is Block.Text || b.text.norm().let { t -> shown.none { it.isNotEmpty() && it.contains(t) } } }
    }

    /**
     * The action of a swipe on a row of [kind]. [overflows]: the row's text
     * was cut (measured on screen, not counted). [hasMore]: there is content
     * behind it ([hasMore]). [read]: already read here (not unread); pass
     * false where the swipe never marks unread (the watch). [verify]: the
     * "got it" asks for a check (a level), so it opens the usual flow.
     */
    public fun intent(kind: Kind, overflows: Boolean, hasMore: Boolean, read: Boolean = false, verify: Boolean = false): Intent = when (kind) {
        Kind.Decision -> Intent.Open
        Kind.Ack -> if (verify) Intent.Open else Intent.Ack
        Kind.Plain -> when {
            overflows || hasMore -> Intent.Open
            read -> Intent.Unread
            else -> Intent.Read
        }
    }

    private fun String.norm(): String = trim().replace(Regex("\\s+"), " ")
}
