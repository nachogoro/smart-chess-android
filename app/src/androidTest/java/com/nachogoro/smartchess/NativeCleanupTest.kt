package com.nachogoro.smartchess

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nachogoro.simplechess.Game
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that `simple-chess-kotlin`'s `NativeCleaner` actually frees native game pointers on ART.
 *
 * This is the check that matters before releasing the library for Android. The other tests prove
 * the class *loads* on Android below API 33 (the original `NoClassDefFoundError` is gone); this one
 * proves the cleanup path *runs*. `NativeCleaner` replaced `java.lang.ref.Cleaner` with a
 * `PhantomReference` + `ReferenceQueue` mechanism, and reference-queue processing is a runtime
 * behaviour of the garbage collector, so it must be verified on ART, not only on the desktop JVM
 * where the unit tests run. A cleaner that silently never fired would not crash; it would leak
 * native memory until a long session ran out.
 *
 * ## Why a churn, not a hold-then-drop
 *
 * The obvious test, holding a batch of games, dropping them and watching native memory fall,
 * does not work reliably on ART. Reclamation is only triggered by garbage collection, and an
 * explicit `System.gc()` under no memory pressure (in a debuggable instrumentation process) is a
 * hint ART is free to ignore, so the dropped games are often never collected and nothing is
 * freed. That is a property of the test, not the library.
 *
 * Churning many short-lived games instead lets real collections happen, which enqueue the phantom
 * references, which the cleaner drains. But a bare `Game.newGame()` allocates almost nothing on the
 * *JVM* heap, so on its own it never pressures ART into collecting the dead games. Each cycle
 * also allocates a throwaway buffer, standing in for the ordinary Java-side allocation that real
 * gameplay (move generation, coroutines, UI) produces and that drives collection in the running app.
 *
 * The native game objects are malloc'd by the C++ library, so they show up in
 * [Debug.getNativeHeapAllocatedSize]: if the cleaner frees them, the in-use native heap stays
 * bounded no matter how many games pass through; if it never fires, the heap climbs by roughly the
 * per-game size times the churn count.
 *
 * The test runs against the sibling `simple-chess-kotlin` project via the build-wide dependency
 * substitution, i.e. exactly the code that would be published.
 */
@RunWith(AndroidJUnit4::class)
class NativeCleanupTest {

    @Before
    fun setUp() {
        // Mirrors SmartChessApplication, which does not run for instrumentation-only contexts.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        System.setProperty("jna.library.path", context.applicationInfo.nativeLibraryDir)
    }

    @Test
    fun churnedGamesDoNotLeakNativeMemory() {
        // Warm up so JNA and the native allocator reach a steady state before the baseline reading.
        repeat(WARMUP) { Game.newGame().getOrThrow() }
        forceCollection()
        val baseline = nativeHeapBytes()

        // Create and immediately discard many games. Each becomes garbage at once; the throwaway
        // buffer per cycle supplies the JVM-heap pressure that drives ART to actually collect them,
        // which enqueues their phantoms and lets the cleaner free the natives.
        @Suppress("UNUSED_VARIABLE")
        var sink = 0
        repeat(CHURN) { i ->
            Game.newGame().getOrThrow()
            val junk = ByteArray(PRESSURE_BYTES)
            sink += junk.size // keep the allocation from being optimised away
            if (i % GC_EVERY == 0) forceCollection()
        }
        forceCollection()

        val growth = nativeHeapBytes() - baseline
        // A dead cleaner would leak about CHURN * per-game bytes here, tens of megabytes. A working
        // one keeps the in-use native heap to a few in-flight games' worth. The ceiling sits far
        // below the leak case and comfortably above steady-state wobble.
        assertTrue(
            "Native heap grew by $growth bytes over $CHURN game create/destroy cycles; the cleaner " +
                "is not keeping pace, so native game pointers are leaking on this device.",
            growth < MAX_GROWTH_BYTES,
        )
    }

    private fun nativeHeapBytes(): Long = Debug.getNativeHeapAllocatedSize()

    /** A few GC cycles with pauses, to let ART collect and the cleaner thread drain the queue. */
    private fun forceCollection() {
        repeat(GC_ROUNDS) {
            System.gc()
            Thread.sleep(GC_PAUSE_MS)
        }
    }

    private companion object {
        const val WARMUP = 200

        // ~1.3 KB of native memory per game was measured on the API 30 emulator, so a dead cleaner
        // would leak ~20 MB over this churn; a working one stays under a couple of MB.
        const val CHURN = 8_000
        const val GC_EVERY = 1_000
        const val MAX_GROWTH_BYTES = 3L * 1024 * 1024

        // Throwaway JVM allocation per cycle, to keep ART collecting the dead games. Stands in for
        // the Java-side garbage that real move-playing produces.
        const val PRESSURE_BYTES = 8 * 1024

        const val GC_ROUNDS = 4
        const val GC_PAUSE_MS = 80L
    }
}
