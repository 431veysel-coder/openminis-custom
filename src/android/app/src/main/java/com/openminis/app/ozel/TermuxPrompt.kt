package com.openminis.app.ozel

import android.content.Context
import com.openminis.app.sandbox.TermuxLayer
import java.io.File

/**
 * [37098] Termux katmanının **sistem istemine** giren kısmı.
 *
 * ## Neden isteme yazmak zorundayız
 *
 * Katman kurulu olduğunda `shell_execute` zaten çalışır — ama model bunu
 * bilmiyor. Sonuç: `apt` var olduğu hâlde model `apk add` dener, ya da
 * "Termux kurulu değil, kuramam" der. Bu, `.14`'te `FANOUT` için öğrenilen
 * dersin aynısı: **araç var, modelin haberi yok** = özellik yok.
 *
 * ## Neden iki ayrı metin (kurulu / değil)
 *
 * Kurulu değilken "apt kullanabilirsin" demek yalan olur; model olmayan bir
 * komutu dener ve kullanıcıya "izin yok" gibi görünen bir hata döner. Kurulu
 * değilken istem, katmanın **nasıl açılacağını** söyler (tek yol: araç
 * zincirleri ekranı) ve modelin kendi başına kurmaya kalkışmasını engeller —
 * 31 MB indirme kullanıcının onayı olmadan yapılmamalı.
 *
 * ## Neden kısa
 *
 * Bu bölüm her turda gönderiliyor. 40 satırlık bir açıklama, bağlam
 * penceresinden yer yer ve her istekte tekrar ücretlendirilir. Kural:
 * modelin **karar vermesi için gereken** bilgi, ansiklopedi değil.
 *
 * ## Neden önbellek dostu
 *
 * Metin iki durumda iki sabit hâl alır (kurulu / değil). Durum tur ortasında
 * değişmez; `apt install` ile katman zaten kurulu kalır. Yani bu bölüm
 * OpenAI/DeepSeek önek önbelleğini bozmaz.
 */
object TermuxPrompt {

    /**
     * Kök dizinde katman var mı — istem üretimi için tek gerçek.
     *
     * [OzelFeatures.Feature.TOOLCHAINS] anahtarına **bağlanmıyor**: o anahtar
     * kurulum ekranını gizler, kurulu katmanı silmez. Katman kuruluyken anahtar
     * kapatılırsa `apt` kabukta hâlâ çalışır; istemin bunu gizlemesi modeli
     * olmayan bir kısıt varsaymaya iter ("apt yok" derken komut çalışır).
     * İstem gerçeği söyler, ayar ekranı neyin görüneceğini söyler.
     */
    fun isReady(ctx: Context): Boolean =
        TermuxLayer.isInstalled(File(ctx.applicationContext.filesDir, TermuxLayer.ROOTFS_DIR_NAME))

    /**
     * Sistem istemine eklenecek bölüm.
     *
     * Katman kurulu değilken de metin yazılıyor — "yok" bilgisi de bir
     * bilgidir: modelin `apt` deneyip hatayı izin sorunu sanmasını engelliyor.
     * Boş string yalnız katman hiç mümkün değilse (bu cihazda bionic yok)
     * dönerdi; o durumda da modelin bilmesi gereken şey aynı: "apt yok,
     * kurmayı denemeyin".
     */
    fun section(ctx: Context): String = if (isReady(ctx)) READY else NOT_READY

    private val READY = """

Termux layer (INSTALLED): the sandbox has two package universes in one root.
- `apk` (Alpine/musl) is the default; `apt`/`pkg` (Termux/bionic) is a second layer at /data/data/com.termux/files/usr.
- Use `apk` first. Use `apt` only for packages Alpine lacks or Termux-specific builds.
- Every `apt`/`pkg` install opens an approval card (new code runs as guest root). A denial is not a quoting problem — ask the user, do not retry another way.
- Editing /etc/apt/sources.list is HIGH and chat-only: afterwards every package comes from the new source. Do not touch it unless the user asks by name.
- `sshd` opens the guest shell to the network, never remembered past this chat. Tell the user while it runs.
- Never mix layers in one command (`apk add x && apt install y`): the second half uses the wrong PATH. Separate calls.
- If asked "where did that come from", say which layer ran it."""

    private val NOT_READY = """

Termux layer (NOT installed):
- Only `apk` (Alpine) is available. There is no `apt`, no `pkg`, no Termux prefix.
- If the user asks for a package that only exists in Termux, do NOT try to install the layer yourself (it downloads ~32 MB). Tell them: Settings › Minis X › Toolchains › "Termux layer" installs it, and what it costs in disk (~110 MB).
- Never invent a Termux command that will fail: say the layer is not installed instead of running `apt` and reporting the error as if it were a permission problem."""
}
