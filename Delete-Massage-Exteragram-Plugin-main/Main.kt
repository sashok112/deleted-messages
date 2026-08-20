package ni.deleted.messages

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.*
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────
//  Модель хранения одного удалённого сообщения
// ─────────────────────────────────────────────────────────────────
data class DeletedEntry(
    val msgId: Int,
    val dialogId: Long,
    val text: String,
    val senderName: String,
    val mediaType: String,
    val originalDate: Long,       // unix-timestamp отправки
    val deletedAt: Long,          // unix-timestamp удаления
    val isOut: Boolean            // исходящее ли сообщение
)

// ─────────────────────────────────────────────────────────────────
//  In-memory кеш  (диалог → список удалённых)
// ─────────────────────────────────────────────────────────────────
object Cache {
    // dialogId -> (msgId -> entry)
    private val data = ConcurrentHashMap<Long, ConcurrentHashMap<Int, DeletedEntry>>()
    // dialogId -> упорядоченный список msgId (для trim)
    private val order = ConcurrentHashMap<Long, ArrayDeque<Int>>()

    var maxPerChat: Int = 200

    fun put(entry: DeletedEntry) {
        val map = data.getOrPut(entry.dialogId) { ConcurrentHashMap() }
        val ord = order.getOrPut(entry.dialogId) { ArrayDeque() }
        if (!map.containsKey(entry.msgId)) ord.addLast(entry.msgId)
        map[entry.msgId] = entry
        trim(entry.dialogId)
    }

    fun get(dialogId: Long, msgId: Int): DeletedEntry? =
        data[dialogId]?.get(msgId)

    fun getDeleted(dialogId: Long): List<DeletedEntry> =
        data[dialogId]?.values?.sortedByDescending { it.deletedAt } ?: emptyList()

    fun totalCount(): Int = data.values.sumOf { it.size }

    fun clearDialog(dialogId: Long) {
        data.remove(dialogId)
        order.remove(dialogId)
    }

    fun clearAll() {
        data.clear()
        order.clear()
    }

    private fun trim(dialogId: Long) {
        val map = data[dialogId] ?: return
        val ord = order[dialogId] ?: return
        while (ord.size > maxPerChat) {
            val old = ord.removeFirst()
            map.remove(old)
        }
    }

    // Сохраняем входящее сообщение ещё до удаления
    fun cacheIncoming(msg: TLRPC.Message, senderName: String) {
        val entry = DeletedEntry(
            msgId       = msg.id,
            dialogId    = dialogIdFromMessage(msg),
            text        = msg.message ?: "",
            senderName  = senderName,
            mediaType   = mediaTypeOf(msg),
            originalDate = msg.date.toLong(),
            deletedAt   = 0L,
            isOut       = msg.out
        )
        put(entry)
    }

    // Помечаем как удалённое
    fun markDeleted(dialogId: Long, msgId: Int): Boolean {
        val entry = data[dialogId]?.get(msgId) ?: run {
            // Ищем по всем диалогам если dialogId не известен
            for ((did, map) in data) {
                map[msgId]?.let {
                    val updated = it.copy(deletedAt = System.currentTimeMillis() / 1000)
                    map[msgId] = updated
                    return true
                }
            }
            return false
        }
        data[dialogId]?.set(msgId, entry.copy(deletedAt = System.currentTimeMillis() / 1000))
        return true
    }

    fun dialogIdFromMessage(msg: TLRPC.Message): Long {
        val peer = msg.peer_id ?: return 0L
        return when {
            peer.user_id    != 0L -> peer.user_id
            peer.chat_id    != 0L -> -peer.chat_id
            peer.channel_id != 0L -> -1000000000000L - peer.channel_id
            else -> 0L
        }
    }

    fun mediaTypeOf(msg: TLRPC.Message): String {
        val m = msg.media ?: return ""
        return when (m) {
            is TLRPC.TL_messageMediaPhoto    -> "📷 Фото"
            is TLRPC.TL_messageMediaDocument -> {
                val doc = m.document
                when {
                    doc?.mime_type?.startsWith("video") == true -> "🎬 Видео"
                    doc?.mime_type?.startsWith("audio") == true -> "🎵 Аудио"
                    doc?.mime_type == "image/webp"              -> "🎭 Стикер"
                    doc?.mime_type == "image/gif"               -> "🎞 GIF"
                    else                                         -> "📄 Документ"
                }
            }
            is TLRPC.TL_messageMediaGeo      -> "📍 Геолокация"
            is TLRPC.TL_messageMediaContact  -> "👤 Контакт"
            is TLRPC.TL_messageMediaPoll     -> "📊 Опрос"
            is TLRPC.TL_messageMediaDice     -> "🎲 Кубик"
            else -> "[медиа]"
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Главный синглтон — точка входа из Python-плагина
// ─────────────────────────────────────────────────────────────────
@SuppressLint("StaticFieldLeak")
class Main private constructor() {

    companion object {
        const val VERSION_CODE = 10   // увеличивай при каждом обновлении

        @Volatile private var INSTANCE: Main? = null

        @JvmStatic
        fun getInstance(): Main = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Main().also { INSTANCE = it }
        }
    }

    private val context: Context get() = ApplicationLoader.applicationContext
    private var notifObserver: NotificationCenter.NotificationCenterDelegate? = null

    // Настройки (читаются из SharedPreferences)
    private val prefs get() = context.getSharedPreferences("deleted_msg_plugin", Context.MODE_PRIVATE)

    var showInChat: Boolean
        get()      = prefs.getBoolean("show_in_chat", true)
        set(value) = prefs.edit().putBoolean("show_in_chat", value).apply()

    var notifyOnDelete: Boolean
        get()      = prefs.getBoolean("notify_delete", false)
        set(value) = prefs.edit().putBoolean("notify_delete", value).apply()

    var showSender: Boolean
        get()      = prefs.getBoolean("show_sender", true)
        set(value) = prefs.edit().putBoolean("show_sender", value).apply()

    var showTime: Boolean
        get()      = prefs.getBoolean("show_time", true)
        set(value) = prefs.edit().putBoolean("show_time", value).apply()

    var showMedia: Boolean
        get()      = prefs.getBoolean("show_media", true)
        set(value) = prefs.edit().putBoolean("show_media", value).apply()

    var maxPerChat: Int
        get()      = prefs.getInt("max_per_chat", 200)
        set(value) { prefs.edit().putInt("max_per_chat", value).apply(); Cache.maxPerChat = value }

    var maxPreviewChars: Int
        get()      = prefs.getInt("max_preview", 300)
        set(value) = prefs.edit().putInt("max_preview", value).apply()

    var highlightColor: String
        get()      = prefs.getString("highlight_color", "#FFD700") ?: "#FFD700"
        set(value) = prefs.edit().putString("highlight_color", value).apply()

    // ──────────── запуск ────────────

    fun start() {
        Cache.maxPerChat = maxPerChat
        subscribeToNotifications()
    }

    fun onUnload() {
        unsubscribeFromNotifications()
    }

    // ──────────── NotificationCenter ────────────

    private fun subscribeToNotifications() {
        val account = UserConfig.selectedAccount
        val nc = NotificationCenter.getInstance(account)

        val observer = NotificationCenter.NotificationCenterDelegate { id, _, args ->
            when (id) {
                // Новые сообщения — кешируем
                NotificationCenter.didReceiveNewMessages -> {
                    val scheduled = args[1] as? Boolean ?: false
                    if (!scheduled) {
                        @Suppress("UNCHECKED_CAST")
                        val msgs = args[0] as? ArrayList<MessageObject> ?: return@NotificationCenterDelegate
                        for (mo in msgs) cacheMessageObject(mo)
                    }
                }
                // Сообщения удалены — помечаем
                NotificationCenter.messagesDeleted -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = args[0] as? ArrayList<Int> ?: return@NotificationCenterDelegate
                    val channelId = args[1] as? Long ?: 0L
                    val dialogId = if (channelId != 0L) -1000000000000L - channelId else 0L
                    for (id2 in ids) {
                        val found = Cache.markDeleted(dialogId, id2)
                        if (found && notifyOnDelete) {
                            AndroidUtilities.runOnUIThread {
                                showDeleteBulletin(dialogId, id2)
                            }
                        }
                    }
                }
            }
        }

        nc.addObserver(observer, NotificationCenter.didReceiveNewMessages)
        nc.addObserver(observer, NotificationCenter.messagesDeleted)
        notifObserver = observer
    }

    private fun unsubscribeFromNotifications() {
        val observer = notifObserver ?: return
        val nc = NotificationCenter.getInstance(UserConfig.selectedAccount)
        nc.removeObserver(observer, NotificationCenter.didReceiveNewMessages)
        nc.removeObserver(observer, NotificationCenter.messagesDeleted)
        notifObserver = null
    }

    // ──────────── кеширование ────────────

    private fun cacheMessageObject(mo: MessageObject) {
        val msg = mo.messageOwner ?: return
        val sender = resolveSenderName(mo)
        Cache.cacheIncoming(msg, sender)
    }

    private fun resolveSenderName(mo: MessageObject): String {
        return try {
            mo.fullName ?: mo.messageOwner?.from_id?.let { "uid:${it.user_id}" } ?: "?"
        } catch (e: Exception) { "?" }
    }

    // ──────────── уведомление об удалении ────────────

    private fun showDeleteBulletin(dialogId: Long, msgId: Int) {
        val entry = Cache.get(dialogId, msgId) ?: return
        val preview = (entry.text.ifEmpty { entry.mediaType }).take(60)
        // Показываем Toast — безопасный способ без зависимости от фрагмента
        Toast.makeText(context, "🗑 ${entry.senderName}: $preview", Toast.LENGTH_SHORT).show()
    }

    // ──────────── публичный API для Python-плагина ────────────

    /** Вызывается из Python: открыть список удалённых сообщений текущего чата */
    fun showDeletedSheet(fragment: BaseFragment?) {
        AndroidUtilities.runOnUIThread {
            openDeletedSheet(fragment)
        }
    }

    /** Вызывается из Python: получить количество удалённых в кеше */
    fun getTotalCached(): Int = Cache.totalCount()

    /** Вызывается из Python: очистить весь кеш */
    fun clearAll() { Cache.clearAll() }

    /** Вызывается из Python: очистить один диалог */
    fun clearDialog(dialogId: Long) { Cache.clearDialog(dialogId) }

    /** Вызывается из Python: текущий dialog_id из фрагмента */
    fun getCurrentDialogId(fragment: BaseFragment?): Long {
        return try {
            fragment?.javaClass?.getMethod("getDialogId")?.invoke(fragment) as? Long ?: 0L
        } catch (e: Exception) { 0L }
    }

    // ──────────── BottomSheet UI ────────────

    @SuppressLint("SetTextI18n")
    private fun openDeletedSheet(fragment: BaseFragment?) {
        val activity = fragment?.parentActivity ?: return
        val dialogId = getCurrentDialogId(fragment)
        val deleted  = if (dialogId != 0L) Cache.getDeleted(dialogId) else emptyList()

        val builder = BottomSheet.Builder(activity)
        builder.setApplyTopPadding(false)
        builder.setApplyBottomPadding(false)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        // ── Заголовок ──
        root.addView(TextView(activity).apply {
            text = "🗑 Удалённые сообщения (${deleted.size})"
            textSize = 18f
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setPadding(0, dp(12), 0, dp(8))
        }, LayoutHelper.createLinear(-1, -2))

        root.addView(View(activity).apply {
            setBackgroundColor(Theme.getColor(Theme.key_divider))
        }, LayoutHelper.createLinear(-1, 1, 0, 0, 0, 8))

        // ── Скроллируемый список ──
        val scroll = ScrollView(activity)
        val inner  = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val sheetRef = arrayOfNulls<BottomSheet>(1)

        if (deleted.isEmpty()) {
            inner.addView(TextView(activity).apply {
                text = "Нет удалённых сообщений в этом чате"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
                setPadding(0, dp(24), 0, dp(24))
            }, LayoutHelper.createLinear(-1, -2))
        } else {
            for (entry in deleted) {
                inner.addView(buildCard(activity, entry, dialogId, sheetRef), LayoutHelper.createLinear(-1, -2, 0, 0, 0, 8))
            }
        }

        scroll.addView(inner)
        root.addView(scroll, LayoutHelper.createLinear(-1, 0, 1f))

        // ── Кнопка «Очистить чат» ──
        if (deleted.isNotEmpty()) {
            root.addView(TextView(activity).apply {
                text = "🗑 Очистить историю этого чата"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Theme.getColor(Theme.key_text_RedRegular))
                setPadding(0, dp(12), 0, dp(4))
                setOnClickListener {
                    Cache.clearDialog(dialogId)
                    sheetRef[0]?.dismiss()
                }
            }, LayoutHelper.createLinear(-1, -2))
        }

        builder.setCustomView(root)
        val sheet = builder.create()
        sheetRef[0] = sheet
        sheet.show()
    }

    @SuppressLint("SetTextI18n")
    private fun buildCard(
        activity: Activity,
        entry: DeletedEntry,
        dialogId: Long,
        sheetRef: Array<BottomSheet?>
    ): View {
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val fmtShort = SimpleDateFormat("HH:mm", Locale.getDefault())

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        }

        // Отправитель
        if (showSender) {
            card.addView(TextView(activity).apply {
                text = "👤 ${entry.senderName}"
                textSize = 12f
                setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
            }, LayoutHelper.createLinear(-1, -2))
        }

        // Текст / медиа
        val displayText = entry.text.take(maxPreviewChars).let {
            if (entry.text.length > maxPreviewChars) "$it…" else it
        }
        card.addView(TextView(activity).apply {
            text = when {
                displayText.isNotEmpty() -> displayText
                entry.mediaType.isNotEmpty() && showMedia -> entry.mediaType
                else -> "[ пустое сообщение ]"
            }
            textSize = 15f
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setPadding(0, dp(4), 0, 0)
        }, LayoutHelper.createLinear(-1, -2))

        // Медиа-тип (если есть и текст, и медиа)
        if (entry.mediaType.isNotEmpty() && displayText.isNotEmpty() && showMedia) {
            card.addView(TextView(activity).apply {
                text = entry.mediaType
                textSize = 12f
                setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
            }, LayoutHelper.createLinear(-1, -2))
        }

        // Время
        if (showTime) {
            val origTime = if (entry.originalDate > 0) fmtShort.format(Date(entry.originalDate * 1000)) else "?"
            val delTime  = if (entry.deletedAt > 0) fmt.format(Date(entry.deletedAt * 1000)) else "?"
            card.addView(TextView(activity).apply {
                text = "✉️ $origTime   🗑 $delTime"
                textSize = 11f
                setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
                setPadding(0, dp(4), 0, 0)
            }, LayoutHelper.createLinear(-1, -2))
        }

        // Кнопка «Убрать»
        card.addView(TextView(activity).apply {
            text = "✕ Убрать из кеша"
            textSize = 12f
            setTextColor(Theme.getColor(Theme.key_text_RedRegular))
            setPadding(0, dp(4), 0, 0)
            setOnClickListener {
                Cache.clearDialog(dialogId)   // или отдельный remove по msgId
                card.visibility = View.GONE
            }
        }, LayoutHelper.createLinear(-2, -2))

        return card
    }

    private fun dp(value: Int): Int = AndroidUtilities.dp(value.toFloat())
}
