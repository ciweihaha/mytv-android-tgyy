package top.yogiczy.mytv.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import top.yogiczy.mytv.data.utils.Constants

/**
 * 日志工具类
 */
class Logger private constructor(
    private val tag: String
) {
    fun d(message: String, throwable: Throwable? = null) {
        Log.d(tag, message, throwable)
        // addHistoryItem(HistoryItem(LevelType.DEBUG, tag, message, throwable?.message))
    }

    fun i(message: String, throwable: Throwable? = null) {
        Log.i(tag, message, throwable)
        addHistoryItem(HistoryItem(LevelType.INFO, tag, message, throwable?.message))
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        addHistoryItem(HistoryItem(LevelType.WARN, tag, message, throwable?.message))
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        addHistoryItem(HistoryItem(LevelType.ERROR, tag, message, throwable?.message))
    }

    fun wtf(message: String, throwable: Throwable? = null) {
        Log.wtf(tag, message, throwable)
        addHistoryItem(HistoryItem(LevelType.ERROR, tag, message, throwable?.message))
    }

    companion object {
        fun create(tag: String) = Logger(tag)

        // 日志历史会被多线程写入（网络线程、播放器回调、主线程），同时被设置页
        // 与局域网接口读取并序列化：无锁的 ArrayList 在并发读写时会丢数据、
        // 甚至在序列化中途抛 ConcurrentModificationException
        private val _history = mutableListOf<HistoryItem>()
        val history: List<HistoryItem>
            get() = synchronized(_history) { _history.toList() }

        // 日志页要实时刷新：history 只是每次现拷的快照，设置页读一次就不再变，
        // 停在日志页上新日志不会出现；另给一条流，入库时在同一把锁里同步推一份新快照
        private val _historyFlow = MutableStateFlow<List<HistoryItem>>(emptyList())
        val historyFlow: StateFlow<List<HistoryItem>> = _historyFlow.asStateFlow()

        fun addHistoryItem(item: HistoryItem) {
            synchronized(_history) {
                // 每条入库时给一个自增编号：设置页日志列表原来拿
                // “时间+标签+内容”当唯一键，同一毫秒里两条一模一样
                // 的日志（比如两次刷新同时失败）会撞键直接崩掉
                _history.add(item.copy(id = ++nextHistoryId))
                if (_history.size > Constants.LOG_HISTORY_MAX_SIZE) _history.removeAt(0)
                _historyFlow.value = _history.toList()
            }
        }

        private var nextHistoryId = 0L
    }

    enum class LevelType {
        DEBUG, INFO, WARN, ERROR
    }

    @Serializable
    data class HistoryItem(
        val level: LevelType,
        val tag: String,
        val message: String,
        val cause: String? = null,
        val time: Long = System.currentTimeMillis(),
        // 入库时由 addHistoryItem 自增赋值，默认 0 只为兼容旧 JSON 与直接构造
        val id: Long = 0,
    )
}

/**
 * 注入日志：日志对象只建一次记下来——Logger 本身没有状态，每次访问
 * 都现建一个只是白白多造对象；其余各处（EpgRepository、IptvRepository、
 * FileCacheRepository、UpdateViewModel）也都是建一次复用，这里与它们保持一致。
 * 标签仍是子类的类名：属性初始化时 javaClass 已是实际的子类，与原来
 * 每次访问时算出来的值相同。
 */
abstract class Loggable(private val tag: String? = null) {
    protected val log: Logger = Logger.create(tag ?: javaClass.simpleName)
}