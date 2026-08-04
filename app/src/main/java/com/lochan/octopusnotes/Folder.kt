package com.lochan.octopusnotes

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val colorHex: String,
    // Parent folder id for nesting; 0 = root.
    val parentId: Long = 0,
    val createdAt: Long = 0,
    var inBin: Boolean = false,
    var deletedAt: Long = 0
)