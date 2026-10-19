package com.putnamworkbook.offline.content.ui

import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalContentClientTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assetContext = instrumentation.context
    private val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var webView: WebView

    @Before
    fun createWebView() {
        instrumentation.runOnMainSync {
            webView = WebView(appContext)
        }
    }

    @After
    fun destroyWebView() {
        instrumentation.runOnMainSync {
            webView.destroy()
        }
    }

    @Test
    fun configureDisablesActiveAndNonAssetContent() {
        val client = LocalContentClient(appContext)

        instrumentation.runOnMainSync {
            client.configure(webView)
            with(webView.settings) {
                assertFalse(javaScriptEnabled)
                assertFalse(allowFileAccess)
                assertFalse(allowContentAccess)
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
                assertFalse(javaScriptCanOpenWindowsAutomatically)
                assertFalse(supportMultipleWindows())
                assertTrue(blockNetworkLoads)
            }
        }
    }

    @Test
    fun bundledHtmlCssFontAndImageResolveButRemoteNavigationIsBlocked() {
        val client = LocalContentClient(assetContext)
        val bundledPaths = listOf(
            "content/fixture/index.html",
            "content/fixture/style.css",
            "content/fixture/fixture.woff2",
            "content/fixture/diagram.svg",
        )

        bundledPaths.forEach { path ->
            val response = client.interceptForTest(Uri.parse("${LocalContentClient.ASSET_BASE_URL}$path"))
            assertNotNull("Expected a local response for $path", response)
            assertEquals(200, response!!.statusCode)
        }

        assertTrue(client.isNavigationAllowed(Uri.parse("${LocalContentClient.ASSET_BASE_URL}content/fixture/index.html")))
        assertFalse(client.isNavigationAllowed(Uri.parse("https://example.com/problem.html")))
        assertFalse(client.isNavigationAllowed(Uri.parse("file:///android_asset/content/fixture/index.html")))
        assertEquals(403, client.interceptForTest(Uri.parse("https://example.com/style.css"))!!.statusCode)
    }

    @Test
    fun generatedHintAndItsKatexStylesAndFontResolveOffline() {
        val client = LocalContentClient(appContext)
        val generatedPaths = listOf(
            "content/putnam/2025/problems/A1/hint-1.html",
            "content/shared/content.css",
            "content/shared/katex/katex.min.css",
            "content/shared/katex/fonts/KaTeX_Main-Regular.woff2",
        )

        generatedPaths.forEach { path ->
            val response = client.interceptForTest(Uri.parse("${LocalContentClient.ASSET_BASE_URL}$path"))
            assertEquals("Expected bundled response for $path", 200, response?.statusCode)
        }
    }

    @Test
    fun rendererLossReportsIntegrityErrorAndRequestsAReplacementView() {
        val errors = mutableListOf<String>()
        var restartCount = 0
        val client = LocalContentClient(
            assetContext = assetContext,
            onIntegrityError = errors::add,
            onRendererGone = { restartCount += 1 },
        )

        instrumentation.runOnMainSync {
            client.configure(webView)
            assertTrue(client.onRenderProcessGone(webView, null))
        }

        assertEquals(1, restartCount)
        assertTrue(errors.single().contains("renderer stopped"))
    }

    @Test
    fun missingHtmlReportsIntegrityErrorWithoutCrashingTheViewer() {
        val errors = mutableListOf<String>()
        val client = LocalContentClient(assetContext, errors::add)

        var result: Result<Unit>? = null
        instrumentation.runOnMainSync {
            client.configure(webView)
            result = client.loadAsset(webView, "content/fixture/missing.html")
        }

        assertTrue(requireNotNull(result).isFailure)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("missing.html"))
        assertTrue(client.isNavigationAllowed(Uri.parse("${LocalContentClient.ASSET_BASE_URL}content/fixture/index.html")))
    }

    @Test
    fun pageReadyCallbackRunsOnlyForVerifiedLocalNavigationWithJavaScriptDisabled() {
        var readyCount = 0
        val client = LocalContentClient(assetContext, onPageReady = { readyCount += 1 })

        instrumentation.runOnMainSync {
            client.configure(webView)
            client.onPageFinished(webView, "${LocalContentClient.ASSET_BASE_URL}content/fixture/index.html")
            client.onPageFinished(webView, "https://example.com/remote.html")
            assertFalse(webView.settings.javaScriptEnabled)
        }

        assertEquals(1, readyCount)
    }
}
