package org.olcbox.app.net

import multiplatform_app.sharedui.generated.resources.Res

/**
 * The sing-box rule-sets the app ships, and what the configs call them.
 *
 * Three binary rule-sets from SagerNet's `rule-set` branches: the lists v2fly
 * publishes as `geosite:category-ru`, `geosite:tld-ru` and `geoip:ru`, compiled
 * to sing-box's format. The TLD list is a file of its own because SagerNet's
 * build of category-ru leaves the bare TLDs out despite the `include:tld-ru`
 * in the v2fly source — without it `sberbank.ru` is matched and `ozon.ru` is
 * not, which is not a list anyone would recognise as "Russia".
 *
 * Bundled rather than downloaded. The networks this mode exists for are the
 * ones where github.com answers slowly or not at all, and a list that arrives
 * after the first connection is a first connection with no bypass in it.
 * `scripts/update-rule-sets.sh` refreshes them and records what it fetched in
 * `scripts/rule-sets.lock`; [RuleSetsTest] refuses a bundle whose bytes do not
 * hash to the values pinned here.
 */
object RuleSets {
    class File(val name: String, val tag: String, val sha256: String)

    val GEOSITE_RU = File(
        name = "geosite-category-ru.srs",
        tag = "geosite-ru",
        sha256 = "c36e157adf86edf7b722b51f3acb93bbb2a7f8083932dae29b4b5ef2c1ced870"
    )
    val GEOSITE_TLD_RU = File(
        name = "geosite-tld-ru.srs",
        tag = "geosite-tld-ru",
        sha256 = "ba979268102429754bdbbf306890f91ac4ee0cf1d2f30eb1fff5eba65e0f9e66"
    )
    val GEOIP_RU = File(
        name = "geoip-ru.srs",
        tag = "geoip-ru",
        sha256 = "1a8115af741918ff24b37b87d3c6da21eccabc58f1eec059e461dca8bac16ff7"
    )

    /** Everything the route rules match on. */
    val all: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU, GEOIP_RU)

    /** The name lists, which is what a DNS rule can match. An IP list has no names. */
    val domains: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU)

    /**
     * Where iOS keeps them, relative to libbox's working directory.
     *
     * Relative because only the extension knows the App Group's absolute path,
     * and libbox resolves a relative `rule_set.path` against the working path
     * it was set up with (`filemanager.BasePath`). The app writes the files
     * there through the Swift bridge; the config never needs the full path.
     */
    const val IOS_RELATIVE_DIR = "rules"

    suspend fun bytes(file: File): ByteArray = Res.readBytes("files/rules/${file.name}")
}
