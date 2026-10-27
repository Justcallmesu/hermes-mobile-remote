package io.github.nideta231.hermesremote.data

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.widget.RemoteViews
import io.github.nideta231.hermesremote.MainActivity
import io.github.nideta231.hermesremote.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widgets (what PC Glance had): PC stats with a CPU/RAM sparkline, and Claude usage.
 * They read the PC the app is using now, over the same pairing. Tapping the body opens the PC
 * page; tapping the time refreshes. A periodic job refreshes every 15 min (Android's minimum).
 */
object PcWidgets {
    const val EXTRA_PAGE = "open_page"
    private const val ACTION_REFRESH = "io.github.nideta231.hermesremote.WIDGET_REFRESH"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The active PC's pairing, and every address to try: the saved one, then the rest the bridge advertised. */
    private fun activePairings(ctx: Context): List<Pairing> {
        val store = CredentialStore(ctx, ProfileStore.pairingFile(ProfileStore(ctx).activeId))
        val p = store.load() ?: return emptyList()
        val eps = EndpointResolver.resolve(p.url, store.bridgeAddresses).map { it.url }
        return (listOf(p.url) + eps).distinct().map { p.copy(url = it) }
    }

    fun refreshAll(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val pairings = activePairings(app)
                if (pairings.isEmpty()) { showMessage(app, "Open Hermes Remote to pair"); return@launch }
                var now: PcNow? = null
                var hist: PcHistory? = null
                var error: String? = null
                for (candidate in pairings) {
                    val c = BridgeClient(candidate)
                    val got = withTimeoutOrNull(15_000) { runCatching { c.monitorNow() to runCatching { c.monitorHistory("1h") }.getOrNull() } }
                    val r = got?.getOrNull()
                    if (r != null) { now = r.first; hist = r.second; break }
                    error = got?.exceptionOrNull()?.let { t ->
                        if (t is BridgeException && t.httpCode == 404) "Update the bridge on your PC" else t.message
                    } ?: "PC not reachable"
                    if (got?.exceptionOrNull() is BridgeException) break // reached the PC; it said no
                }
                if (now == null) { showMessage(app, "Offline: ${error ?: "no connection"}"); return@launch }
                renderPc(app, now, hist)
                renderClaude(app, now.claude)
            } finally {
                done?.invoke()
            }
        }
    }

    private fun ids(ctx: Context, cls: Class<*>) = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    private fun refreshIntent(ctx: Context, cls: Class<*>): PendingIntent =
        PendingIntent.getBroadcast(ctx, cls.hashCode(), Intent(ctx, cls).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_PAGE, "PC")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun stamp() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun wire(ctx: Context, v: RemoteViews, cls: Class<*>) {
        v.setOnClickPendingIntent(R.id.widget_root, openIntent(ctx))
        v.setOnClickPendingIntent(R.id.widget_time, refreshIntent(ctx, cls))
    }

    fun showMessage(ctx: Context, msg: String) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for ((cls, layout) in listOf(PcStatsWidget::class.java to R.layout.widget_pc, ClaudeUsageWidget::class.java to R.layout.widget_claude)) {
            for (id in ids(ctx, cls)) {
                val v = RemoteViews(ctx.packageName, layout)
                v.setTextViewText(R.id.widget_sub, msg)
                wire(ctx, v, cls)
                mgr.partiallyUpdateAppWidget(id, v)
            }
        }
    }

    private fun renderPc(ctx: Context, now: PcNow, hist: PcHistory?) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val s = now.stats
        val spark = hist?.takeIf { it.ts.size >= 2 }?.let {
            sparkline(listOf(it["cpu"] to Color.parseColor("#3DDC97"), it["mem"] to Color.parseColor("#5B8DEF")))
        }
        for (id in ids(ctx, PcStatsWidget::class.java)) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_pc)
            v.setTextViewText(R.id.widget_host, now.host.ifBlank { "PC" } + " · up " + fmtUptime(s.uptimeS))
            v.setTextViewText(R.id.widget_time, stamp())
            v.setTextViewText(R.id.widget_cpu, "CPU %.0f%%".format(s.cpu))
            v.setTextViewText(R.id.widget_mem, "RAM %.0f%%".format(s.mem))
            v.setTextViewText(R.id.widget_temp, s.temp?.let { "%.0f°C".format(it) } ?: "-- °C")
            v.setTextViewText(R.id.widget_sub, now.alerts.firstOrNull() ?: ("Disk %.0f%% · ↓%s ↑%s".format(s.disk ?: 0.0, fmtRate(s.rx), fmtRate(s.tx))))
            if (spark != null) v.setImageViewBitmap(R.id.widget_spark, spark)
            wire(ctx, v, PcStatsWidget::class.java)
            mgr.updateAppWidget(id, v)
        }
    }

    private fun renderClaude(ctx: Context, c: ClaudeUsage) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in ids(ctx, ClaudeUsageWidget::class.java)) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_claude)
            wire(ctx, v, ClaudeUsageWidget::class.java)
            v.setTextViewText(R.id.widget_time, stamp())
            v.setTextViewText(R.id.widget_title, "Claude" + (c.plan?.let { " · " + it.replaceFirstChar { ch -> ch.uppercase() } } ?: ""))
            if (!c.hasNumbers) {
                v.setTextViewText(R.id.widget_sub, c.error ?: "No Claude data yet")
                mgr.updateAppWidget(id, v); continue
            }
            val session = c.session ?: 0.0
            val weekly = c.weekly ?: 0.0
            v.setTextViewText(R.id.widget_l1, "Session %.0f%%  %s".format(session, fmtResetsIn(c.sessionResetsAt)))
            v.setProgressBar(R.id.widget_p1, 100, session.toInt().coerceIn(0, 100), false)
            v.setTextViewText(R.id.widget_l2, "Weekly %.0f%%  %s".format(weekly, fmtResetsIn(c.weeklyResetsAt)))
            v.setProgressBar(R.id.widget_p2, 100, weekly.toInt().coerceIn(0, 100), false)
            v.setTextViewText(R.id.widget_sub, if (!c.ok && c.error != null) "stale: ${c.error}"
                else c.checkedAt?.let { "PC checked " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it * 1000)) } ?: "")
            mgr.updateAppWidget(id, v)
        }
    }

    private fun sparkline(series: List<Pair<List<Double?>, Int>>): Bitmap {
        val w = 480; val h = 120
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; strokeJoin = Paint.Join.ROUND }
        val grid = Paint().apply { color = Color.parseColor("#262D3D"); strokeWidth = 1f }
        for (k in 0..2) c.drawLine(0f, (h - 1) * k / 2f, w.toFloat(), (h - 1) * k / 2f, grid)
        for ((vals, color) in series) {
            if (vals.size < 2) continue
            val path = Path(); var started = false
            vals.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                val x = w * i / (vals.size - 1f); val y = h - 3 - (h - 6) * (v / 100.0).coerceIn(0.0, 1.0).toFloat()
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            p.color = color
            c.drawPath(path, p)
        }
        return bmp
    }

    open class Base : AppWidgetProvider() {
        override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            RefreshJob.schedule(ctx)
            val pending = goAsync()
            refreshAll(ctx) { pending.finish() }
        }

        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_REFRESH) {
                showMessage(ctx, "Refreshing…")
                val pending = goAsync()
                refreshAll(ctx) { pending.finish() }
            } else super.onReceive(ctx, intent)
        }

        override fun onDisabled(ctx: Context) {
            // The last widget of either kind is gone: stop the periodic refresh.
            val any = ids(ctx, PcStatsWidget::class.java).isNotEmpty() || ids(ctx, ClaudeUsageWidget::class.java).isNotEmpty()
            if (!any) ctx.getSystemService(JobScheduler::class.java)?.cancel(RefreshJob.JOB_ID)
        }
    }
}

class PcStatsWidget : PcWidgets.Base()
class ClaudeUsageWidget : PcWidgets.Base()

/** Refresh the widgets every 15 min while there is a network. */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        PcWidgets.refreshAll(this) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        const val JOB_ID = 4201

        fun schedule(ctx: Context) {
            runCatching {
                val js = ctx.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(JOB_ID) != null) return
                js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, RefreshJob::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build())
            }
        }
    }
}
