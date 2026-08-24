plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ruzakj.speedometer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ruzakj.speedometer"
        minSdk = 23
        targetSdk = 36
        versionCode = 8
        versionName = "2.6"
    }

    sourceSets["main"].assets.srcDir(rootProject.file("background"))

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Inject the background renderer into the existing native Canvas dashboard at build time.
// This keeps the 2.8 MB image out of source code while preserving the current UI architecture.
val applyDashboardBackground by tasks.registering {
    doLast {
        val source = file("src/main/java/com/ruzakj/speedometer/MainActivityV2.kt")
        var text = source.readText()
        if (!text.contains("__MOTO_BG_RENDERER_V1__")) {
            text = text.replace(
                "import android.graphics.Canvas",
                "import android.graphics.Bitmap\nimport android.graphics.BitmapFactory\nimport android.graphics.Canvas"
            )
            text = text.replace(
                "private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val fill=Paint(Paint.ANTI_ALIAS_FLAG);",
                "/* __MOTO_BG_RENDERER_V1__ */\n        private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val fill=Paint(Paint.ANTI_ALIAS_FLAG);private val backgroundBitmap:Bitmap? = runCatching { context.assets.open(\"file_00000000c6fc8207a0ceed05d02e1f1c.png\").use { BitmapFactory.decodeStream(it) } }.getOrNull();"
            )
            text = text.replace(
                "override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(BG);",
                "override fun onDraw(c:Canvas){super.onDraw(c);drawBackground(c);"
            )
            text = text.replace(
                "private fun shownSpeed()=",
                "private fun drawBackground(c:Canvas){\\n            c.drawColor(BG)\\n            val bmp=backgroundBitmap ?: return\\n            val bw=bmp.width.toFloat(); val bh=bmp.height.toFloat(); val vw=width.toFloat(); val vh=height.toFloat()\\n            if(bw<=0f || bh<=0f || vw<=0f || vh<=0f) return\\n            // Center-crop with one uniform scale: no stretching, no distortion.\\n            val scale=max(vw/bw, vh/bh); val dw=bw*scale; val dh=bh*scale\\n            val left=(vw-dw)/2f; val top=(vh-dh)/2f\\n            val dst=RectF(left, top, left+dw, top+dh)\\n            paint.alpha=68\\n            paint.shader=null\\n            c.drawBitmap(bmp, null, dst, paint)\\n            // Dark veil keeps text/gauge readable while retaining the motorcycle artwork.\\n            fill.style=Paint.Style.FILL; fill.color=BG; fill.alpha=118\\n            c.drawRect(0f,0f,vw,vh,fill)\\n            paint.alpha=255\\n        }\\n        private fun shownSpeed()="
            )
            text = text.replace("\\n", "\n")
            source.writeText(text)
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(applyDashboardBackground)
}
