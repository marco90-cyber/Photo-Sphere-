package com.marco.photosphere

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Colori dell'interfaccia di scatto. */
object Hud {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val DIM = 0xB3FFFFFF.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val AMBER = 0xFFFFB300.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
    const val BLUE = 0xFF0A84FF.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val PANEL = 0x8C000000.toInt()
    const val PANEL_STROKE = 0x33FFFFFF
}

/** Sfumatura scura in alto o in basso: rende leggibili i comandi sopra qualsiasi sfondo. */
fun Context.scrim(fromTop: Boolean): View = View(this).apply {
    background = GradientDrawable(
        if (fromTop) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(0xC0000000.toInt(), 0x66000000, 0x00000000)
    )
}

/** Pannello scuro arrotondato con bordo sottile. */
fun Context.hudPanel(radiusDp: Int = 12): GradientDrawable = GradientDrawable().apply {
    cornerRadius = dp(radiusDp).toFloat()
    setColor(Hud.PANEL)
    setStroke(dp(1), Hud.PANEL_STROKE)
}

/** Etichetta piccola in alto (qualità, timer): tocca per cambiare valore. */
fun Context.hudChip(text: String, iconRes: Int = 0): TextView = TextView(this).apply {
    this.text = text
    setTextColor(Color.WHITE)
    textSize = 13f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    gravity = Gravity.CENTER
    setPadding(dp(12), dp(7), dp(12), dp(7))
    background = hudPanel(18)
    isClickable = true
    if (iconRes != 0) {
        val d = androidx.core.content.ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (d != null) {
            d.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = dp(5)
        }
    }
}

/** Riga dei dati tecnici (ISO, tempo, blocco esposizione): caratteri a larghezza fissa, non "ballano". */
fun Context.hudReadout(sizeSp: Float = 12f): TextView = TextView(this).apply {
    setTextColor(Color.WHITE)
    textSize = sizeSp
    typeface = Typeface.MONOSPACE
    letterSpacing = 0.02f
    setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
}

/** Messaggio guida con pallino colorato che indica lo stato (bianco, ambra, verde, blu). */
class HintView(ctx: Context) : TextView(ctx) {
    private val dotDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Hud.WHITE)
    }
    private var shown: CharSequence = ""
    private var tone = Hud.WHITE

    init {
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(18), ctx.dp(10))
        background = ctx.hudPanel(22)
        val s = ctx.dp(8)
        dotDrawable.setBounds(0, 0, s, s)
        setCompoundDrawables(dotDrawable, null, null, null)
        compoundDrawablePadding = ctx.dp(10)
    }

    fun show(text: CharSequence, color: Int = Hud.WHITE) {
        if (color != tone) {
            tone = color
            dotDrawable.setColor(color)
        }
        if (text.toString() == shown.toString()) return
        shown = text
        this.text = text
        animate().cancel()
        alpha = 0.35f
        animate().alpha(1f).setDuration(160).start()
    }
}

/** Pulsante tondo con icona e piccola etichetta sotto (Annulla, Pausa, Chiudi). */
class HudButton(ctx: Context, iconRes: Int, label: String?, sizeDp: Int = 52) : LinearLayout(ctx) {
    private val icon = ImageView(ctx)
    private val text = TextView(ctx)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        val pad = ctx.dp(sizeDp) / 4
        icon.setImageResource(iconRes)
        icon.setPadding(pad, pad, pad, pad)
        icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Hud.PANEL)
            setStroke(ctx.dp(1), Hud.PANEL_STROKE)
        }
        addView(icon, LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp)))
        if (label != null) {
            text.text = label
            text.setTextColor(Hud.DIM)
            text.textSize = 11f
            text.gravity = Gravity.CENTER
            text.setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
            addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(5) })
        }
    }

    fun set(iconRes: Int, label: String) {
        icon.setImageResource(iconRes)
        text.text = label
    }

    /** Evidenzia il pulsante (es. pausa attiva). */
    fun setActive(on: Boolean) {
        (icon.background as? GradientDrawable)?.setColor(if (on) Hud.AMBER else Hud.PANEL)
        icon.setColorFilter(if (on) Color.BLACK else Color.WHITE)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val down = isPressed
        icon.animate().cancel()
        icon.animate().scaleX(if (down) 0.9f else 1f).scaleY(if (down) 0.9f else 1f).setDuration(90).start()
    }
}

/**
 * Cornice del mirino sopra l'anteprima: bordo sottile e quattro angoli marcati, come nelle
 * fotocamere professionali. Cambia colore con lo stato e lampeggia a ogni scatto.
 */
class ViewfinderFrameView(ctx: Context) : View(ctx) {
    var tone = Hud.WHITE
        set(v) { if (field != v) { field = v; invalidate() } }

    private var flashAlpha = 0f
    private var flashAnim: ValueAnimator? = null

    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1).toFloat()
    }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(3).toFloat()
        strokeCap = Paint.Cap.SQUARE
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(3).toFloat()
        color = 0x55000000
    }
    private val flash = Paint().apply { style = Paint.Style.FILL; color = Color.WHITE }

    /** Lampo bianco breve: conferma visiva dello scatto. */
    fun flash() {
        flashAnim?.cancel()
        flashAnim = ValueAnimator.ofFloat(0.55f, 0f).apply {
            duration = 220
            addUpdateListener { flashAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        flashAnim?.cancel()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (flashAlpha > 0f) {
            flash.alpha = (flashAlpha * 255).toInt()
            c.drawRect(0f, 0f, w, h, flash)
        }
        val o = corner.strokeWidth / 2f
        // ombra esterna leggera: stacca la cornice da sfondi chiari
        c.drawRect(o, o, w - o, h - o, shadow)
        thin.color = tone
        thin.alpha = 110
        c.drawRect(o, o, w - o, h - o, thin)
        corner.color = tone
        val l = minOf(w, h) * 0.13f
        // alto a sinistra
        c.drawLine(o, o, o + l, o, corner); c.drawLine(o, o, o, o + l, corner)
        // alto a destra
        c.drawLine(w - o, o, w - o - l, o, corner); c.drawLine(w - o, o, w - o, o + l, corner)
        // basso a sinistra
        c.drawLine(o, h - o, o + l, h - o, corner); c.drawLine(o, h - o, o, h - o - l, corner)
        // basso a destra
        c.drawLine(w - o, h - o, w - o - l, h - o, corner); c.drawLine(w - o, h - o, w - o, h - o - l, corner)
    }
}
