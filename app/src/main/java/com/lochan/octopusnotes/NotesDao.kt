package com.lochan.octopusnotes

import androidx.room.*

@Dao
interface NotesDao {

    @Insert
    suspend fun insertFolder(folder: Folder)

    @Update
    suspend fun updateFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)

    @Query("SELECT * FROM folders WHERE inBin = 0 ORDER BY name ASC")
    suspend fun getAllFolders(): List<Folder>

    @Query("SELECT * FROM folders WHERE parentId = :parentId AND inBin = 0 ORDER BY name ASC")
    suspend fun getFoldersInParent(parentId: Long): List<Folder>

    @Query("SELECT * FROM folders WHERE parentId = :parentId")
    suspend fun getFoldersByParent(parentId: Long): List<Folder>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: Long): Folder?

    @Query("SELECT * FROM folders WHERE tagColorHex = :colorHex AND inBin = 0 ORDER BY name ASC")
    suspend fun getFoldersByTag(colorHex: String): List<Folder>

    @Query("SELECT * FROM folders WHERE inBin = 1 ORDER BY deletedAt DESC")
    suspend fun getBinFolders(): List<Folder>

    @Query("SELECT * FROM folders WHERE inBin = 1 AND deletedAt < :threshold")
    suspend fun getOldBinFolders(threshold: Long): List<Folder>

    @Query("SELECT * FROM folders ORDER BY id ASC")
    fun getAllFoldersIncludingBin(): List<Folder>

    @Insert
    suspend fun insertNotebook(notebook: Notebook): Long

    @Query("SELECT * FROM notebooks WHERE isFavorite = 1 AND inBin = 0 ORDER BY title ASC")
    suspend fun getFavoriteNotebooks(): List<Notebook>

    @Query("SELECT * FROM drawings WHERE notebookId = :notebookId")
    suspend fun getDrawingsForNotebook(notebookId: Long): List<Drawing>

    @Query("SELECT * FROM notebooks WHERE id = :id LIMIT 1")
    suspend fun getNotebookById(id: Long): Notebook?

    @Update
    suspend fun updateNotebook(notebook: Notebook)

    @Delete
    suspend fun deleteNotebook(notebook: Notebook)

    @Query("SELECT * FROM notebooks WHERE folderId = 0 AND inBin = 0 ORDER BY title ASC")
    suspend fun getRootNotebooks(): List<Notebook>

    @Query("SELECT * FROM notebooks WHERE folderId = :folderId AND inBin = 0 ORDER BY title ASC")
    suspend fun getNotebooksInFolder(folderId: Long): List<Notebook>

    @Query("SELECT * FROM notebooks WHERE folderId = :folderId")
    suspend fun getAllNotebooksInFolder(folderId: Long): List<Notebook>

    @Query("SELECT * FROM notebooks WHERE inBin = 0 ORDER BY title ASC")
    suspend fun getAllNotebooks(): List<Notebook>

    @Query("SELECT * FROM notebooks WHERE tagColorHex = :colorHex AND inBin = 0 ORDER BY title ASC")
    suspend fun getNotebooksByTag(colorHex: String): List<Notebook>

    @Query("SELECT * FROM notebooks WHERE inBin = 1 ORDER BY deletedAt DESC")
    suspend fun getBinNotebooks(): List<Notebook>

    @Query("SELECT * FROM notebooks WHERE inBin = 1 AND deletedAt < :threshold")
    suspend fun getOldBinNotebooks(threshold: Long): List<Notebook>

    @Query("SELECT * FROM notebooks ORDER BY id ASC")
    fun getAllNotebooksIncludingBin(): List<Notebook>

    @Query("UPDATE notebooks SET lastModified = :time WHERE id = :id")
    suspend fun touchModified(id: Long, time: Long)

    @Query("UPDATE notebooks SET lastOpened = :time WHERE id = :id")
    suspend fun touchOpened(id: Long, time: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDrawing(drawing: Drawing)

    @Query("SELECT * FROM drawings WHERE notebookId = :notebookId AND pageNumber = :pageNumber LIMIT 1")
    suspend fun getDrawingForPage(notebookId: Long, pageNumber: Int): Drawing?

    @Query("UPDATE drawings SET pageNumber = pageNumber + 1 WHERE notebookId = :notebookId AND pageNumber >= :startPage")
    suspend fun shiftPageNumbers(notebookId: Long, startPage: Int)
}