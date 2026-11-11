package top.yogiczy.mytv.data.repositories.git.parser

import top.yogiczy.mytv.data.entities.GitRelease
import top.yogiczy.mytv.data.utils.Constants

class GithubGitReleaseParser : GitReleaseParser {
    override fun isSupport(url: String): Boolean {
        return url.contains("github.com")
    }

    override suspend fun parse(data: String): GitRelease {
        return GitReleaseParser.parseRelease(data, Constants.GITHUB_PROXY)
    }
}
