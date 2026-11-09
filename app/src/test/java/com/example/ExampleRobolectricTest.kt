package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CatalogRepository
import com.example.data.FavoritesRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context and verify bundled catalog and favorites`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("RadioWeb", appName)

        val catalogRepo = CatalogRepository(context)
        val loaded = catalogRepo.loadInitialCatalog()
        assertTrue(loaded.stations.isNotEmpty())
        assertTrue(loaded.stations.any { it.id == "radiolacch" && it.name == "RADIO LAC" })
        assertEquals(180, loaded.canonicalGenresAlphabetical.size)
        assertEquals(12, loaded.frequentGenresTop12.size)

        val favRepo = FavoritesRepository(context)
        favRepo.saveFavoriteIds(setOf("radiolacch", "swissjazz"))
        assertEquals(setOf("radiolacch", "swissjazz"), favRepo.loadFavoriteIds())
    }

    @Test
    fun `verify RadioPlaybackService binds MediaBrowserService and returns non-null session`() {
        val controller = org.robolectric.Robolectric.buildService(com.example.player.RadioPlaybackService::class.java)
        val service = controller.create().get()
        val bindIntent = android.content.Intent("android.media.browse.MediaBrowserService")
        val binder = service.onBind(bindIntent)
        org.junit.Assert.assertNotNull("MediaBrowserService binder must not be null", binder)

        // Inspect the MediaLibrarySession and LegacyStub via reflection to see what onGetRoot returns for com.android.bluetooth
        val sessionField = com.example.player.RadioPlaybackService::class.java.getDeclaredField("mediaLibrarySession")
        sessionField.isAccessible = true
        val session = sessionField.get(service)
        org.junit.Assert.assertNotNull("mediaLibrarySession must not be null", session)
    }
}
