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

// Blend the motorcycle artwork into the dashboard rather than hiding it behind opaque cards.
// The original aspect ratio is preserved with a uniform center-crop. UI surfaces become glass-like
// so the artwork remains visible behind the gauge, telemetry and action controls.
val applyDashboardBackground by tasks.registering {
    doLast {
        val source = file("src/main/java/com/ruzakj/speedometer/MainActivityV2.kt")
        var text = source.readText()
        if (!text.contains("__MOTO_BG_RENDERER_V2__")) {
            text = text.replace(
                "import android.graphics.Canvas",
                "import android.graphics.Bitmap\nimport android.graphics.BitmapFactory\nimport android.graphics.Canvas"
            )
            text = text.replace(
                "private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val fill=Paint(Paint.ANTI_ALIAS_FLAG);",
                "/* __MOTO_BG_RENDERER_V2__ */\n        private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val fill=Paint(Paint.ANTI_ALIAS_FLAG);private val backgroundBitmap:Bitmap? = runCatching { context.assets.open(\"file_00000000c6fc8207a0ceed05d02e1f1c.png\").use { BitmapFactory.decodeStream(it) } }.getOrNull();"
            )
            text = text.replace(
                "override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(BG);",
                "override fun onDraw(c:Canvas){super.onDraw(c);drawBackground(c);"
            )
            text = text.replace("premiumCard(c,", "glassCard(c,")
            text = text.replace("statCard(c,", "glassStatCard(c,")
            text = text.replace("button(c,", "glassButton(c,")
            text = text.replace(
                "private fun shownSpeed()=",
                """
        private fun drawBackground(c:Canvas){
            c.drawColor(BG)
            val bmp=backgroundBitmap ?: return
            val bw=bmp.width.toFloat(); val bh=bmp.height.toFloat(); val vw=width.toFloat(); val vh=height.toFloat()
            if(bw<=0f || bh<=0f || vw<=0f || vh<=0f) return
            val scale=max(vw/bw, vh/bh)
            val dw=bw*scale; val dh=bh*scale
            val left=(vw-dw)/2f; val top=(vh-dh)/2f
            val dst=RectF(left,top,left+dw,top+dh)
            paint.shader=null; paint.alpha=132
            c.drawBitmap(bmp,null,dst,paint)
            // A very soft veil creates a cohesive TFT look without flattening the artwork.
            fill.style=Paint.Style.FILL; fill.color=BG; fill.alpha=42
            c.drawRect(0f,0f,vw,vh,fill)
            paint.alpha=255
        }
        private fun glassCard(c:Canvas,r:RectF){
            val radius=df(22f)
            fill.style=Paint.Style.FILL; fill.color=BG; fill.alpha=92
            c.drawRoundRect(r,radius,radius,fill)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=df(1f); paint.alpha=120
            paint.shader=LinearGradient(r.left,r.top,r.right,r.bottom,CYAN,PURPLE,Shader.TileMode.CLAMP)
            c.drawRoundRect(r,radius,radius,paint)
            paint.shader=null; paint.alpha=255
        }
        private fun glassStatCard(c:Canvas,r:RectF,label:String,value:String,unit:String,accent:Int){
            fill.style=Paint.Style.FILL; fill.color=BG; fill.alpha=108
            c.drawRoundRect(r,df(16f),df(16f),fill)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=df(1f); paint.color=accent; paint.alpha=100
            c.drawRoundRect(r,df(16f),df(16f),paint)
            text(c,label,r.left+df(10f),r.top+df(17f),df(8f),MUTED,true,Paint.Align.LEFT)
            text(c,value,r.left+df(10f),r.top+df(43f),df(18f),TEXT,true,Paint.Align.LEFT)
            if(unit.isNotEmpty()) text(c,unit,r.right-df(10f),r.top+df(43f),df(8f),accent,true,Paint.Align.RIGHT)
            paint.alpha=255
        }
        private fun glassButton(c:Canvas,r:RectF,label:String,accent:Int){
            fill.style=Paint.Style.FILL; fill.color=BG; fill.alpha=76
            c.drawRoundRect(r,df(14f),df(14f),fill)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=df(1.2f); paint.color=accent; paint.alpha=190
            c.drawRoundRect(r,df(14f),df(14f),paint)
            text(c,label,r.centerX(),r.centerY()+df(3f),df(9f),accent,true,Paint.Align.CENTER)
            paint.alpha=255
        }
        private fun shownSpeed()="""
            )
            text = text.replace("\\n", "\n")
            source.writeText(text)
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(applyDashboardBackground)
}
