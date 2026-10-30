package com.marco.photosphere

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Finestra di aggiornamento (versione con le View di Android) per [AppUpdate]. */
object AppUpdateWindow {
    private var dialog: AlertDialog? = null
    private var listener: AppUpdate.Listener? = null

    fun show(activity: Activity) {
        dialog?.let { if (it.isShowing) return }
        val dp = activity.resources.displayMetrics.density
        val pad = (24 * dp).toInt()
        val text = TextView(activity).apply { textSize = 15f }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }
        val detail = TextView(activity).apply { textSize = 13f; alpha = 0.8f }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(text); addView(bar); addView(detail)
        }
        val d = AlertDialog.Builder(activity)
            .setTitle("Aggiornamenti")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(" ", null)
            .setNegativeButton(" ", null)
            .setNeutralButton(" ", null)
            .create()
        dialog = d

        fun button(which: Int, label: String?, action: (() -> Unit)?) {
            val b = d.getButton(which) ?: return
            if (label == null) { b.visibility = View.GONE; return }
            b.visibility = View.VISIBLE
            b.text = label
            b.setOnClickListener { action?.invoke() }
        }

        fun render() {
            if (!d.isShowing) return
            val s = AppUpdate.state
            val downloading = s is AppUpdate.State.Downloading
            // Schermo acceso durante il download
            if (downloading) d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else d.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            d.setCancelable(!downloading)
            d.setCanceledOnTouchOutside(!downloading)

            fun progress(done: Long, total: Long, speed: Long = -1) {
                bar.visibility = View.VISIBLE
                bar.isIndeterminate = total <= 0
                if (total > 0) bar.progress = (done * 1000 / total).toInt().coerceIn(0, 1000)
                detail.visibility = View.VISIBLE
                detail.text = AppUpdate.progressText(done, total, speed)
            }
            bar.visibility = View.GONE
            detail.visibility = View.GONE
            val close = { AppUpdate.hide(); d.dismiss() }
            when (s) {
                AppUpdate.State.Idle, AppUpdate.State.Checking -> {
                    d.setTitle("Controllo aggiornamenti…")
                    text.text = "Cerco l'ultima versione su GitHub…"
                    bar.visibility = View.VISIBLE; bar.isIndeterminate = true
                    button(DialogInterface.BUTTON_POSITIVE, "Chiudi", close)
                    button(DialogInterface.BUTTON_NEGATIVE, null, null)
                    button(DialogInterface.BUTTON_NEUTRAL, null, null)
                }
                is AppUpdate.State.UpToDate -> {
                    d.setTitle("Nessun aggiornamento")
                    text.text = "Hai già l'ultima versione (${s.installed})."
                    button(DialogInterface.BUTTON_POSITIVE, "OK", close)
                    button(DialogInterface.BUTTON_NEGATIVE, null, null)
                    button(DialogInterface.BUTTON_NEUTRAL, null, null)
                }
                is AppUpdate.State.Available -> {
                    d.setTitle("Nuova versione disponibile")
                    val r = s.release
                    text.text = buildString {
                        append(r.name).append(" (installata: ").append(AppUpdate.installedName(activity)).append(")")
                        if (r.size > 0) append("\nDimensione: ").append(AppUpdate.mb(r.size))
                        if (r.notes.isNotBlank()) append("\n\n").append(r.notes.take(1500))
                    }
                    button(DialogInterface.BUTTON_POSITIVE, "Scarica") { AppUpdate.download(activity) }
                    button(DialogInterface.BUTTON_NEGATIVE, "Chiudi", close)
                    button(DialogInterface.BUTTON_NEUTRAL, null, null)
                }
                is AppUpdate.State.Downloading -> {
                    d.setTitle("Download in corso…")
                    text.text = s.release.name + "\nLo schermo resta acceso fino alla fine del download."
                    progress(s.done, s.total, s.speed)
                    button(DialogInterface.BUTTON_POSITIVE, "Pausa") { AppUpdate.pause() }
                    button(DialogInterface.BUTTON_NEGATIVE, "Annulla") { AppUpdate.cancel(activity) }
                    button(DialogInterface.BUTTON_NEUTRAL, null, null)
                }
                is AppUpdate.State.Paused -> {
                    d.setTitle("Download in pausa")
                    text.text = s.release.name + "\nTocca «Riprendi» per continuare da dove si è fermato."
                    progress(s.done, s.total)
                    button(DialogInterface.BUTTON_POSITIVE, "Riprendi") { AppUpdate.download(activity) }
                    button(DialogInterface.BUTTON_NEGATIVE, "Annulla") { AppUpdate.cancel(activity) }
                    button(DialogInterface.BUTTON_NEUTRAL, "Chiudi", close)
                }
                is AppUpdate.State.Ready -> {
                    d.setTitle("Pronto da installare")
                    text.text = "${s.release.name} è stata scaricata. Tocca «Installa» e conferma nella finestra di Android."
                    button(DialogInterface.BUTTON_POSITIVE, "Installa") { AppUpdate.install(activity) }
                    button(DialogInterface.BUTTON_NEGATIVE, "Chiudi", close)
                    button(DialogInterface.BUTTON_NEUTRAL, null, null)
                }
                is AppUpdate.State.Failed -> {
                    d.setTitle("Aggiornamento non riuscito")
                    text.text = s.message
                    if (s.release != null) {
                        if (s.done > 0) progress(s.done, s.total)
                        button(DialogInterface.BUTTON_POSITIVE, "Riprendi") { AppUpdate.download(activity) }
                        button(DialogInterface.BUTTON_NEGATIVE, "Annulla") { AppUpdate.cancel(activity) }
                    } else {
                        button(DialogInterface.BUTTON_POSITIVE, "Riprova") { AppUpdate.check(activity) }
                        button(DialogInterface.BUTTON_NEGATIVE, "Apri GitHub") { AppUpdate.openPage(activity) }
                    }
                    button(DialogInterface.BUTTON_NEUTRAL, "Chiudi", close)
                }
            }
        }

        val l = AppUpdate.Listener { render() }
        listener = l
        AppUpdate.addListener(l)
        d.setOnShowListener { render() }
        d.setOnDismissListener {
            AppUpdate.removeListener(l)
            if (AppUpdate.state !is AppUpdate.State.Downloading) AppUpdate.hide()
            if (dialog === d) dialog = null
        }
        AppUpdate.open(activity)
        d.show()
    }
}
