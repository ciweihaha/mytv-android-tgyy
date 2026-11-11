package top.yogiczy.mytv.data.repositories.git.parser

import top.yogiczy.mytv.data.entities.GitRelease

class GiteeGitReleaseParser : GitReleaseParser {
    override fun isSupport(url: String): Boolean {
        return url.contains("gitee.com")
    }

    override suspend fun parse(data: String): GitRelease {
        return GitReleaseParser.parseRelease(data)
    }
}
