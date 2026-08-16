package com.example.cardprinter

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.print.PrintHelper
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var imgFront: ImageView
    private lateinit var imgBack: ImageView
    private lateinit var btnFront: Button
    private lateinit var btnBack: Button
    private lateinit var btnPrintFront: Button
    private lateinit var btnPrintBack: Button
    private lateinit var btnPrintDuplex: Button

    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
    private var currentPhotoUri: Uri? = null
    private var isSelectingFront = true

    // بطاقة قياسية ID-1
    private val cardWidthMm = 85.6f
    private val cardHeightMm = 53.98f

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { startCrop(it) }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentPhotoUri != null) {
            startCrop(currentPhotoUri!!)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            showImageSourceDialog()
        } else {
            Toast.makeText(this, "يجب منح الأذونات", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imgFront = findViewById(R.id.imgFront)
        imgBack = findViewById(R.id.imgBack)
        btnFront = findViewById(R.id.btnFront)
        btnBack = findViewById(R.id.btnBack)
        btnPrintFront = findViewById(R.id.btnPrintFront)
        btnPrintBack = findViewById(R.id.btnPrintBack)
        btnPrintDuplex = findViewById(R.id.btnPrintDuplex)

        btnFront.setOnClickListener {
            isSelectingFront = true
            checkPermissionsAndShowDialog()
        }

        btnBack.setOnClickListener {
            isSelectingFront = false
            checkPermissionsAndShowDialog()
        }

        btnPrintFront.setOnClickListener {
            val fb = frontBitmap
            if (fb == null) {
                Toast.makeText(this, "اختر الصورة الأمامية أولاً", Toast.LENGTH_SHORT).show()
            } else {
                printSinglePage(fb, "10 نسخ - الوجه الأمامي")
            }
        }

        btnPrintBack.setOnClickListener {
            val bb = backBitmap
            if (bb == null) {
                Toast.makeText(this, "اختر الصورة الخلفية أولاً", Toast.LENGTH_SHORT).show()
            } else {
                printSinglePage(bb, "10 نسخ - الوجه الخلفي")
            }
        }

        btnPrintDuplex.setOnClickListener {
            val fb = frontBitmap
            val bb = backBitmap
            if (fb == null || bb == null) {
                Toast.makeText(this, "اختر الصورتين الأمامية والخلفية أولاً", Toast.LENGTH_SHORT).show()
            } else {
                printDuplex(fb, bb)
            }
        }
    }

    private fun checkPermissionsAndShowDialog() {
        val permissions = mutableListOf(android.Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            showImageSourceDialog()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun showImageSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("اختر مصدر الصورة")
            .setItems(arrayOf("الكاميرا", "الاستوديو / المعرض")) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> galleryLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun openCamera() {
        val photoFile = try {
            createImageFile()
        } catch (e: IOException) {
            Toast.makeText(this, "خطأ في إنشاء الملف", Toast.LENGTH_SHORT).show()
            return
        }
        currentPhotoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        cameraLauncher.launch(currentPhotoUri)
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("CARD_${timeStamp}_", ".jpg", storageDir)
    }

    // فتح شاشة القص عشان المستخدم يظبط حواف البطاقة بدقة
    private fun startCrop(sourceUri: Uri) {
        val destFile = File(cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
        val destUri = Uri.fromFile(destFile)

        val options = UCrop.Options()
        options.setCompressionQuality(95)
        options.setFreeStyleCropEnabled(true)
        options.setToolbarTitle(if (isSelectingFront) "قص الصورة الأمامية" else "قص الصورة الخلفية")

        UCrop.of(sourceUri, destUri)
            .withAspectRatio(cardWidthMm, cardHeightMm)
            .withOptions(options)
            .start(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UCrop.REQUEST_CROP && resultCode == RESULT_OK && data != null) {
            val resultUri = UCrop.getOutput(data)
            if (resultUri != null) {
                try {
                    val inputStream = contentResolver.openInputStream(resultUri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (bitmap != null) {
                        if (isSelectingFront) {
                            frontBitmap = bitmap
                            imgFront.setImageBitmap(bitmap)
                        } else {
                            backBitmap = bitmap
                            imgBack.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "فشل تحميل الصورة المقصوصة", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (resultCode == UCrop.RESULT_ERROR && data != null) {
            Toast.makeText(this, "فشل قص الصورة", Toast.LENGTH_SHORT).show()
        }
    }

    // طباعة ورقة A4 واحدة فيها 10 نسخ من صورة واحدة (وش أو ظهر لوحده)
    private fun printSinglePage(cardBitmap: Bitmap, jobName: String) {
        val pageBitmap = createA4PageWithTenCards(cardBitmap)
        val printHelper = PrintHelper(this)
        printHelper.scaleMode = PrintHelper.SCALE_MODE_FIT
        printHelper.printBitmap(jobName, pageBitmap)
    }

    // ينشئ Bitmap بحجم A4 (300 DPI) فيه 10 نسخ من نفس البطاقة بالحجم الحقيقي
    private fun createA4PageWithTenCards(cardBitmap: Bitmap): Bitmap {
        val dpi = 300
        val a4WidthMm = 210f
        val a4HeightMm = 297f

        val a4WidthPx = (a4WidthMm / 25.4f * dpi).toInt()
        val a4HeightPx = (a4HeightMm / 25.4f * dpi).toInt()
        val cardWidthPx = (cardWidthMm / 25.4f * dpi).toInt()
        val cardHeightPx = (cardHeightMm / 25.4f * dpi).toInt()

        val page = Bitmap.createBitmap(a4WidthPx, a4HeightPx, Bitmap.Config.ARGB_8888)
        page.setDensity(dpi)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val marginX = 40f
        val marginY = 50f
        val gapX = 30f
        val gapY = 25f

        val availableWidth
