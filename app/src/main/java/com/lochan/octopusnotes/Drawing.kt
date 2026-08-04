package com.lochan.octopusnotes

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "drawings")
data class Drawing(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val notebookId: Long,
    val pageNumber: Int,
    val filePath: String // Path to the saved .png file for this page's drawing
)