package de.trei.cambackup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.trei.cambackup.compress.ImageShrinker
import de.trei.cambackup.compress.ShrinkResult
import de.trei.cambackup.compress.SkipReason
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.CompressionSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class ImageShrinkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shrinker = ImageShrinker(context)
    private lateinit var dir: File
    private val day = 24L * 60 * 60 * 1000
    private val old = System.currentTimeMillis() - 400 * day

    @Before fun setUp() {
        dir = File(context.cacheDir, "shrinker-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        shrinker.clearCache()
    }

    /**
     * 4000×3000-JPEG (Qualität 100, mit Rauschen wie bei einem echten Foto), links rot, rechts blau,
     * gespeichert „liegend“ mit EXIF-Ausrichtung 90° → angezeigt hochkant mit Rot oben.
     */
    private fun createPhoto(name: String = "IMG_TEST.jpg", software: String? = "HDR+ 1.0"): File {
        val bmp = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawRect(0f, 0f, 2000f, 3000f, Paint().apply { color = Color.rgb(220, 30, 30) })
        canvas.drawRect(2000f, 0f, 4000f, 3000f, Paint().apply { color = Color.rgb(30, 30, 220) })
        val rnd = Random(42)
        val p = Paint()
        repeat(60_000) {
            p.color = Color.argb(70, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            canvas.drawCircle(rnd.nextFloat() * 4000, rnd.nextFloat() * 3000, 2f + rnd.nextFloat() * 6, p)
        }
        val file = File(dir, name)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }

        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:01 12:34:56")
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            setAttribute(ExifInterface.TAG_MODEL, "X1")
            setAttribute(ExifInterface.TAG_F_NUMBER, "1.8")
            software?.let { setAttribute(ExifInterface.TAG_SOFTWARE, it) }
            setLatLong(52.52, 13.405)
            saveAttributes()
        }
        return file
    }

    private fun itemFor(file: File, mime: String = "image/jpeg", taken: Long = old) = MediaItem(
        id = file.name.hashCode().toLong(),
        uri = Uri.fromFile(file),
        displayName = file.name,
        relativePath = "DCIM/Camera/",
        mimeType = mime,
        sizeBytes = file.length(),
        dateTakenMillis = taken,
        isVideo = false,
    )

    @Test fun shrinksRotatesAndKeepsExif() = runBlocking {
        val original = createPhoto()
        val result = shrinker.shrink(itemFor(original), CompressionSettings(imageMaxEdge = 3072, jpegQuality = 85))
        assertTrue("Ergebnis: $result", result is ShrinkResult.Ready)
        val ready = result as ShrinkResult.Ready
        assertTrue("kleiner: ${ready.newBytes} < ${ready.originalBytes}", ready.newBytes < ready.originalBytes * 0.8)
        assertEquals(ready.file.length(), ready.newBytes)

        // Abmessungen: hochkant, längste Kante 3072
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ready.file.path, bounds)
        assertEquals(2304, bounds.outWidth)
        assertEquals(3072, bounds.outHeight)

        // Pixel sind gedreht: Rot (vorher links) ist jetzt oben
        val out = BitmapFactory.decodeFile(ready.file.path)
        val top = out.getPixel(out.width / 2, out.height / 8)
        val bottom = out.getPixel(out.width / 2, out.height * 7 / 8)
        assertTrue("oben rot", Color.red(top) > Color.blue(top))
        assertTrue("unten blau", Color.blue(bottom) > Color.red(bottom))

        val exif = ExifInterface(ready.file)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
        assertEquals("2024:05:01 12:34:56", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("TestCam", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("X1", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals(1.8, exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0), 0.01)
        assertEquals("HDR+ 1.0 (CamBackup)", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals("2304", exif.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION))
        val latLong = exif.latLong
        assertNotNull(latLong)
        assertEquals(52.52, latLong!![0], 0.001)
        assertEquals(13.405, latLong[1], 0.001)

        // Das Original bleibt unverändert
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface(original).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
    }

    @Test fun removesGpsWhenDisabled() = runBlocking {
        val result = shrinker.shrink(itemFor(createPhoto()), CompressionSettings(keepGps = false)) as ShrinkResult.Ready
        val exif = ExifInterface(result.file)
        assertNull(exif.latLong)
        assertEquals("2024:05:01 12:34:56", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
    }

    @Test fun originalSizeKeepsResolution() = runBlocking {
        val result = shrinker.shrink(itemFor(createPhoto()), CompressionSettings(imageMaxEdge = null, jpegQuality = 80)) as ShrinkResult.Ready
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(result.file.path, bounds)
        assertEquals(3000, bounds.outWidth)
        assertEquals(4000, bounds.outHeight)
    }

    @Test fun alreadyCompressedIsSkipped() = runBlocking {
        val first = shrinker.shrink(itemFor(createPhoto()), CompressionSettings()) as ShrinkResult.Ready
        val again = File(dir, "IMG_AGAIN.jpg").also { first.file.copyTo(it) }
        assertEquals(ShrinkResult.Skipped(SkipReason.ALREADY_COMPRESSED), shrinker.shrink(itemFor(again), CompressionSettings()))
    }

    @Test fun skipsRecentHeicAndSmallSavingsButCompressesMotionPhotos() = runBlocking {
        val photo = createPhoto()
        assertEquals(
            ShrinkResult.Skipped(SkipReason.TOO_RECENT),
            shrinker.shrink(itemFor(photo, taken = System.currentTimeMillis()), CompressionSettings(minAgeDays = 30)),
        )
        assertEquals(
            ShrinkResult.Skipped(SkipReason.UNSUPPORTED_FORMAT),
            shrinker.shrink(itemFor(photo, mime = "image/heic"), CompressionSettings()),
        )
        val motion = createPhoto(name = "PXL_20240101_120000000.MP.jpg")
        // Bewegte Fotos werden wie normale verkleinert – die Bewegung bleibt nur im Original auf dem Medium
        assertTrue(shrinker.shrink(itemFor(motion), CompressionSettings()) is ShrinkResult.Ready)
        // Qualität 95 in Originalgröße spart bei einem Q100-Bild sicher keine 90 %
        assertEquals(
            ShrinkResult.Skipped(SkipReason.TOO_LITTLE_SAVING),
            shrinker.shrink(itemFor(photo), CompressionSettings(imageMaxEdge = null, jpegQuality = 95, minSavingsPercent = 90)),
        )
    }
}
