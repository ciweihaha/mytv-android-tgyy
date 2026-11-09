package top.yogiczy.mytv.data.repositories.epg.fetcher

import okhttp3.Response

class DefaultEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return true
    }

    override fun fetch(response: Response): String {
        // 无扩展名 / 带参数的地址走这里：按内容嗅探解码，而不是像原先
        // 那样直接返回空节目单
        return EpgFetcher.decodeBody(response)
    }
}
