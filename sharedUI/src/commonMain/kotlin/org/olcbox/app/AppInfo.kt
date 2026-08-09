package org.olcbox.app

data class AppInfo(
    val name: String,
    val version: String,
    /**
     * Which build this is: the short git SHA it came from, with `*` when the tree
     * had uncommitted edits, or "local" when there was no git to ask.
     *
     * The version cannot answer that question here. Our patch number is the CI run
     * number, so a nightly and a local Xcode archive both call themselves 1.0.273.
     */
    val build: String = ""
)

object CurrentAppInfo {
    val value: AppInfo = AppInfo(
        name = GeneratedAppInfo.NAME,
        version = GeneratedAppInfo.VERSION,
        build = GeneratedAppInfo.BUILD
    )

    val userAgent: String = "${value.name}/${value.version}"
}
