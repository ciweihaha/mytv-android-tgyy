package top.yogiczy.mytv.data.repositories.epg.fetcher

import okhttp3.Response

class XmlEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return url.endsWith(".xml")
    }

    override fun fetch(response: Response): String {
        // 空响应体（如 204）时返回空串，由上层按空节目单处理，而不是 NPE
        return response.body?.string() ?: ""
    }
}