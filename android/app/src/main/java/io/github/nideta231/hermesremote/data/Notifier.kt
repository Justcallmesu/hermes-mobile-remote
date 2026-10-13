package io.github.nideta231.hermesremote.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.nideta231.hermesremote.MainActivity
import io.github.nideta231.hermesremote.R

/**
 * The app's notifications. Only events worth interrupting someone for: a turn that needs a
 * decision (approval, a clarify question), and a turn that ended while the app was off screen.
 * Tool progress stays silent.
 *
 * Channels use the system defaults (sound, vibration, heads-up for approvals) so the user's own
 * Android notification settings apply; tapping opens the session the notification is about.
 */
object Notifier {
    private const val CHANNEL_APPROVALS = "approvals"
    private const val CHANNEL_RESULTS = "results"
    private const val CHANNEL_WATCH = "watching"

    const val WATCH_ID = 1
    const val EXTRA_OPEN_SESSION = "open_session"
    /** The Hermes profile that session belongs to (absent: the default profile). */
    const val EXTRA_PROFILE = "open_profile"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_APPROVALS, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Hermes is paused until you answer"
            },
            NotificationChannel(CHANNEL_RESULTS, "Finished tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A task in a chat open on this device finished, failed or was stopped"
            },
            // Required by Android for a foreground service; silent and collapsed.
            NotificationChannel(CHANNEL_WATCH, "Task in progress", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Hermes Remote stays connected for a running task"
                setShowBadge(false)
            },
        ))
    }

    fun watchNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setContentTitle("Hermes is working")
            .setContentText("You'll be notified when it finishes or needs you")
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context, null, null, 0))
            .build()

    fun postTurnFinished(context: Context, key: String, sessionId: String?, profile: String?, title: String?, status: String, reply: String?) {
        val where = title?.takeIf { it.isNotBlank() } ?: "Hermes"
        val (heading, fallback) = when (status) {
            "complete" -> where to "Finished."
            "error" -> "Failed: $where" to "The task ended with an error. Tap to see what happened."
            "interrupted" -> "Stopped: $where" to "The task was stopped before it finished."
            else -> where to "Task ended ($status)."
        }
        val body = reply?.let(::plain)?.takeIf { it.isNotBlank() }?.take(600) ?: fallback
        // One "finished" alert per session: a newer turn replaces the older one.
        post(context, CHANNEL_RESULTS, resultId(sessionId ?: key), heading, body, sessionId, profile, NotificationCompat.CATEGORY_MESSAGE)
    }

    fun postApprovalNeeded(context: Context, requestId: String, sessionId: String?, profile: String?, title: String?, request: ApprovalRequest) {
        val body = listOfNotNull(request.description?.takeIf { it.isNotBlank() }, request.command?.takeIf { it.isNotBlank() }?.let { "$ $it" })
            .joinToString("\n").ifBlank { "Hermes is waiting for your decision." }
        post(context, CHANNEL_APPROVALS, questionId(requestId),
            "Approval needed${title?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
            body.take(600), sessionId, profile, NotificationCompat.CATEGORY_REMINDER, requestId)
    }

    fun postQuestion(context: Context, requestId: String, sessionId: String?, profile: String?, title: String?, question: String?) {
        post(context, CHANNEL_APPROVALS, questionId(requestId),
            "Hermes has a question${title?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
            (question ?: "Hermes is waiting for your answer.").take(600), sessionId, profile, NotificationCompat.CATEGORY_REMINDER, requestId)
    }

    fun clearQuestion(context: Context, requestId: String) {
        NotificationManagerCompat.from(context).cancel(questionId(requestId))
        posted.remove(requestId)
    }

    /** Drop alerts for a session the user just opened; they have seen it. */
    fun clearSession(context: Context, sessionId: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(resultId(sessionId))
        posted.filterValues { it == sessionId }.keys.toList().forEach { rid -> nm.cancel(questionId(rid)); posted.remove(rid) }
    }

    /** requestId → sessionId of questions currently posted, so opening a chat can clear them. */
    private val posted = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun post(context: Context, channel: String, id: Int, title: String, body: String,
                     sessionId: String?, profile: String?, category: String, requestId: String? = null) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (requestId != null && sessionId != null) posted[requestId] = sessionId
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setCategory(category)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, sessionId, profile, id))
            .build()
        runCatching { nm.notify(id, n) } // SecurityException if permission was revoked mid-flight
    }

    private fun openApp(context: Context, sessionId: String?, profile: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply {
                if (sessionId != null) putExtra(EXTRA_OPEN_SESSION, sessionId)
                if (profile != null) putExtra(EXTRA_PROFILE, profile)
            }
        return PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Markdown in a notification shows as raw symbols; strip the common marks. */
    internal fun plain(md: String): String = md
        .replace(Regex("```[\\s\\S]*?```"), "[code]")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun resultId(key: String) = 0x10000 + (key.hashCode() and 0xffff)
    private fun questionId(requestId: String) = 0x20000 + (requestId.hashCode() and 0xffff)
}
