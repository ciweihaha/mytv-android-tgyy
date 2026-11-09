package top.yogiczy.mytv.data.repositories.epg.fetcher

import okhttp3.Response

class XmlGzEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return EpgFetcher.matchExtension(url, ".gz")
    }

    override fun fetch(response: Response): String {
        return EpgFetcher.decodeBody(response)
    }
}
