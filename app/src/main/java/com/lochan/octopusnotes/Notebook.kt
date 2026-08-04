package com.lochan.octopusnotes

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notebooks")
data class Notebook(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var title: String,
    var folderId: Long = 0,
    var pdfPath: String? = null,
    var isFavorite: Boolean = false,
    var createdAt: Long = 0,
    var lastModified: Long = 0,
    var lastOpened: Long = 0,
    var inBin: Boolean = false,
    var deletedAt: Long = 0
)