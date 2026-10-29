package com.example.xdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Куда приложение кладёт файлы.
 *
 * Это регрессия на конкретную ошибку «не удалось сохранить готовый GIF в
 * Movies/XDownloader». Платформа (AOSP MediaProvider.ensureFileColumns) разрешает
 * картинкам только DCIM и Pictures, поэтому GIF в Movies записать нельзя ни через
 * одну коллекцию MediaStore. Тест фиксирует раскладку: видео — Movies, всё
 * остальное — Pictures.
 */
class DownloadStorageTest {

    @Test
    fun gifGoesToPicturesNotMovies() {
        val path = DownloadStorage.relativePathFor("image/gif")

        assertEquals("Pictures/XDownloader", path)
        assertTrue(
            "GIF в Movies платформа отклонит — это и был баг сохранения",
            !path.startsWith("Movies")
        )
    }

    @Test
    fun videoGoesToMovies() {
        assertEquals("Movies/XDownloader", DownloadStorage.relativePathFor("video/mp4"))
        assertEquals("Movies/XDownloader", DownloadStorage.relativePathFor("video/webm"))
    }

    @Test
    fun photosGoToPictures() {
        assertEquals("Pictures/XDownloader", DownloadStorage.relativePathFor("image/jpeg"))
        assertEquals("Pictures/XDownloader", DownloadStorage.relativePathFor("image/png"))
        assertEquals("Pictures/XDownloader", DownloadStorage.relativePathFor("image/webp"))
    }

    @Test
    fun mimeIsDetectedFromExtension() {
        assertEquals("image/gif", DownloadStorage.mimeFromName("Post_anim.gif"))
        assertEquals("video/mp4", DownloadStorage.mimeFromName("Post.mp4"))
        assertEquals("image/jpeg", DownloadStorage.mimeFromName("photo.JPG"))
    }

    /** Имя попадает в MediaStore, поэтому разделители пути в нём недопустимы. */
    @Test
    fun nameIsSanitizedForMediaStore() {
        assertEquals("a_b_c", DownloadStorage.sanitizeName("a/b\\c"))
        assertEquals("Post", DownloadStorage.sanitizeName("  Post  "))
        assertTrue(DownloadStorage.sanitizeName("   ").isNotEmpty())
        assertTrue(!DownloadStorage.sanitizeName(".hidden").startsWith("."))
    }

    @Test
    fun onlyMediaFilesAreListed() {
        assertTrue(DownloadStorage.isMediaFile("clip.mp4"))
        assertTrue(DownloadStorage.isMediaFile("anim.GIF"))
        assertTrue(!DownloadStorage.isMediaFile("clip.mp4.part"))
        assertTrue(!DownloadStorage.isMediaFile("notes.txt"))
    }
}
