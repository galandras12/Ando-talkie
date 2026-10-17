package hu.ando.talkie

import android.app.Application
import hu.ando.talkie.core.Prefs
import hu.ando.talkie.core.StorageCipher
import hu.ando.talkie.data.AppDb
import hu.ando.talkie.data.Repo
import hu.ando.talkie.net.LinkManager

class TalkieApp : Application() {
    lateinit var prefs: Prefs
    lateinit var repo: Repo
    lateinit var link: LinkManager

    override fun onCreate() {
        super.onCreate()
        LinkManager.createChannels(this)
        prefs = Prefs(this)
        repo = Repo(this, AppDb.create(this), StorageCipher())
        link = LinkManager(this, repo, prefs)
    }
}
