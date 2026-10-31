package com.arelove.app.home

/**
 * Item de match para la lista de MatchesListFragment.
 */
data class MatchItem(
    val matchId: String,
    val otroUserId: String,
    val otroNickname: String,
    val otroFotoUrl: String?,
    val matchCreatedAt: String
)