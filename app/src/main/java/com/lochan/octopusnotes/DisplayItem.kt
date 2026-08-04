package com.lochan.octopusnotes

sealed class DisplayItem {
    data class FolderItem(val folder: Folder) : DisplayItem()
    data class NotebookItem(val notebook: Notebook) : DisplayItem()
}