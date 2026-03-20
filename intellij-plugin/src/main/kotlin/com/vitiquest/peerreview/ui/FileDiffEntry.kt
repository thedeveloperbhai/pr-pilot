package com.vitiquest.peerreview.ui

data class FileDiffEntry(
    val displayLabel: String,   // e.g. "src/Foo.kt"
    val statusTag: String,      // "ADDED" | "DELETED" | "MODIFIED" | "RENAMED"
    val oldPath: String,
    val newPath: String,
    val oldText: String,        // reconstructed old file content
    val newText: String         // reconstructed new file content
)
