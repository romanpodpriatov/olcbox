package org.olcbox.app.ui.features.home

/** What a remove control on the board is asking to delete, while it asks. */
sealed interface PendingRemoval {
    /** One location the user added by hand. */
    data class Location(val id: String, val title: String) : PendingRemoval

    /** A server list, and everything it brought in. */
    data class ServerList(val url: String, val title: String, val count: Int) : PendingRemoval
}
