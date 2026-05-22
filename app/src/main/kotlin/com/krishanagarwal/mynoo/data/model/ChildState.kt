package com.krishanagarwal.mynoo.data.model

/**
 * Lightweight representation of the currently active child profile.
 * Equivalent to the React Native ChildContext.
 *
 * [name]     — child's display name (Firestore document key under kids/)
 * [classNum] — "6" … "10" or empty if not yet assigned
 */
data class ChildState(
    val name:     String = "",
    val classNum: String = "",
) {
    val isSelected: Boolean get() = name.isNotBlank()
}

/**
 * Full child profile as stored in Firestore kids/{name}.
 */
data class Child(
    val name:      String = "",
    val age:       String = "",
    val classNum:  String = "",   // "6"–"10" or ""
    val createdAt: String = "",
) {
    fun toState() = ChildState(name = name, classNum = classNum)
}

