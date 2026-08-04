package com.lochan.octopusnotes

import androidx.room.*

@Dao
interface NotesDao {

    // --- Folder Queries ---

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

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: Long): Folder?

    @Query("UPDATE notebooks SET folderId = :newParent WHERE folderId = :folderId")
    suspend fun moveNotebooksToParent(folderId: Long, newParent: Long)

    @Query("UPDATE folders SET parentId = :newParent WHERE parentId = :folderId")
    suspend fun moveSubfoldersToParent(folderId: Long, newParent: Long)

    // --- Bin Queries (Folders) ---
    @Query("SELECT * FROM folders WHERE inBin = 1 ORDER BY deletedAt DESC")
    suspend fun getBinFolders(): List<Folder>
    
    @Query("SELECT * FROM folders WHERE inBin = 1 AND deletedAt < :threshold")
    suspend fun getOldBinFolders(threshold: Long): List<Folder>

    // --- Notebook Queries ---

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

    @Query("SELECT * FROM notebooks WHERE inBin = 0 ORDER BY title ASC")
    suspend fun getAllNotebooks(): List<Notebook>

    // --- Bin Queries (Notebooks) ---
    @Query("SELECT * FROM notebooks WHERE inBin = 1 ORDER BY deletedAt DESC")
    suspend fun getBinNotebooks(): List<Notebook>
    
    @Query("SELECT * FROM notebooks WHERE inBin = 1 AND deletedAt < :threshold")
    suspend fun getOldBinNotebooks(threshold: Long): List<Notebook>

    @Query("UPDATE notebooks SET lastModified = :time WHERE id = :id")
    suspend fun touchModified(id: Long, time: Long)

    @Query("UPDATE notebooks SET lastOpened = :time WHERE id = :id")
    suspend fun touchOpened(id: Long, time: Long)

    /**
     * "Safe-delete" for folders. This finds all notebooks inside a given folder
     * and moves them to the root (folderId = 0) before the folder is deleted.
     */
    @Query("UPDATE notebooks SET folderId = 0 WHERE folderId = :folderId")
    suspend fun moveNotebooksToRoot(folderId: Long)

    // --- Drawing Queries ---

    /**
     * Inserts a new drawing record. If a drawing for that specific page
     * already exists, it will be replaced with the new one.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDrawing(drawing: Drawing)

    /**
     * Retrieves the saved drawing data for a specific page within a specific notebook.
     */
    @Query("SELECT * FROM drawings WHERE notebookId = :notebookId AND pageNumber = :pageNumber LIMIT 1")
    suspend fun getDrawingForPage(notebookId: Long, pageNumber: Int): Drawing?

    /**
     * Increments the page number of all drawings in a notebook that are greater than or equal to the specified page.
     * This is used when inserting a new page.
     */
    @Query("UPDATE drawings SET pageNumber = pageNumber + 1 WHERE notebookId = :notebookId AND pageNumber >= :startPage")
    suspend fun shiftPageNumbers(notebookId: Long, startPage: Int)
}