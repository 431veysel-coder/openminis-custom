package com.openminis.app.device

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log

/**
 * [H-014] GPU adını **kendi bağlamımızla** oku.
 *
 * Neden bu dosya var: `.55`'te Ayarlar → Cihaz kaynakları ekranı açılır
 * açılmaz uygulama SIGSEGV ile ölüyordu. `AndroidDeviceProbe.gpuRenderer()`
 * `GLES20.glGetString(GL_RENDERER)` çağırıyordu ve o an geçerli bir GL bağlamı
 * yoktu; `glGetString` **null pointer** döndürmek yerine `glGetString+44`
 * içinde `0xdd0` adresine erişip süreci öldürüyor (04:46 çökme kaydı, bkz.
 * HATA-DEFTERI H-014).
 *
 * `runCatching` bunu yakalayamaz: SIGSEGV bir Java istisnası değil, sinyaldir.
 * Tek doğru çözüm çağrıyı **geçerli bir bağlamın içinde** yapmak. Burada
 * 1×1 pbuffer yüzeyli, ekransız (headless) bir EGL bağlamı kuruyoruz; kurulum
 * başarısız olursa hiç çağırmıyoruz ve boş dize dönüyoruz — uydurma yok.
 *
 * Çağrı ana iş parçacığında yapılabilir (EGL bağlamı bağımsızdır) ama
 * genellikle arka planda çağrılır; sürücü ilk açılışta 10-50 ms harcayabilir.
 */
// kapi-muaf: glGetString çağrısı bu dosyanın kurduğu EGL bağlamı içinde
// yapılıyor (`eglMakeCurrent` başarılıysa); bağlamsız çağrı H-014'tü.
object GpuQuery {

    private const val TAG = "GpuQuery"

    /** `GL_RENDERER`, ya da bağlam kurulamazsa boş dize. Asla istisna atmaz. */
    fun renderer(): String {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var config: EGLConfig? = null
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return ""
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return ""

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)) return ""
            if (numConfigs[0] <= 0) return ""
            config = configs[0] ?: return ""

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE,
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return ""

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE,
            )
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return ""

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return ""

            // Tek güvenli nokta: bağlam gerçekten current.
            GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "EGL sorgusu düştü: ${t.javaClass.simpleName}: ${t.message}")
            ""
        } finally {
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }
}
