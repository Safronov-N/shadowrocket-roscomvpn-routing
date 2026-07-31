#!/usr/bin/env kotlin

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

data class RouteSpec(
    val fileName: String,
    val siteKey: String,
    val ipKey: String
)

val routingUrl =
    "https://raw.githubusercontent.com/hydraponique/roscomvpn-routing/main/HAPP/DEFAULT.JSON"
val geositeBaseUrl =
    "https://raw.githubusercontent.com/hydraponique/roscomvpn-geosite/master/data"
val geoipBaseUrl =
    "https://raw.githubusercontent.com/hydraponique/roscomvpn-geoip/master/release/text"

val httpClient =
    HttpClient
        .newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

val downloadCache = mutableMapOf<String, String>()

fun download(url: String): String =
    downloadCache.getOrPut(url) {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("User-Agent", "shadowrocket-roscomvpn-routing")
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Не удалось скачать $url: HTTP ${response.statusCode()}"
        }
        response.body()
    }

fun jsonArray(json: String, key: String): List<String> {
    val block =
        Regex(
            pattern = "\"${Regex.escape(key)}\"\\s*:\\s*\\[(.*?)]",
            option = RegexOption.DOT_MATCHES_ALL
        ).find(json)?.groupValues?.get(1)
            ?: error("В DEFAULT.JSON отсутствует массив $key")

    return Regex("\"([^\"]+)\"")
        .findAll(block)
        .map { it.groupValues[1] }
        .toList()
}

fun jsonString(json: String, key: String): String =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]+)\"")
        .find(json)
        ?.groupValues
        ?.get(1)
        ?: error("В DEFAULT.JSON отсутствует строка $key")

fun normalizedLines(text: String): List<String> =
    text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()

fun regexToWildcard(pattern: String): String? =
    when (pattern) {
        """^github-production-release-asset-[0-9a-zA-Z]{6}\.s3\.amazonaws\.com$""" ->
            "github-production-release-asset-??????.s3.amazonaws.com"
        else -> null
    }

fun geositeRules(
    category: String,
    visited: MutableSet<String> = mutableSetOf()
): List<String> {
    check(visited.add(category)) { "Циклический include в geosite: $category" }

    val sourceUrl = "$geositeBaseUrl/$category"
    val rules =
        normalizedLines(download(sourceUrl)).flatMap { sourceLine ->
            val line =
                sourceLine
                    .substringBefore(" #")
                    .substringBefore(" @")
                    .trim()

            when {
                line.startsWith("include:") ->
                    geositeRules(line.removePrefix("include:"), visited)
                line.startsWith("domain:") ->
                    listOf("DOMAIN-SUFFIX,${line.removePrefix("domain:")}")
                line.startsWith("full:") ->
                    listOf("DOMAIN,${line.removePrefix("full:")}")
                line.startsWith("keyword:") ->
                    listOf("DOMAIN-KEYWORD,${line.removePrefix("keyword:")}")
                line.startsWith("regexp:") -> {
                    val pattern = line.removePrefix("regexp:")
                    val wildcard =
                        regexToWildcard(pattern)
                            ?: error("Неподдерживаемое regexp в $category: $pattern")
                    listOf("DOMAIN-WILDCARD,$wildcard")
                }
                ":" !in line -> listOf("DOMAIN-SUFFIX,$line")
                else -> error("Неподдерживаемая строка в $category: $line")
            }
        }

    visited.remove(category)
    return rules
}

fun geoipRules(category: String): List<String> =
    normalizedLines(download("$geoipBaseUrl/$category.txt")).map { cidr ->
        if (":" in cidr) {
            "IP-CIDR6,$cidr,no-resolve"
        } else {
            "IP-CIDR,$cidr,no-resolve"
        }
    }

fun categoryName(reference: String, prefix: String): String {
    val expectedPrefix = "$prefix:"
    require(reference.startsWith(expectedPrefix)) {
        "Ожидалась ссылка $expectedPrefix, получено: $reference"
    }
    return reference.removePrefix(expectedPrefix)
}

val routing = download(routingUrl)
val lastUpdated = jsonString(routing, "LastUpdated")
val specs =
    listOf(
        RouteSpec("BLOCK.list", "BlockSites", "BlockIp"),
        RouteSpec("PROXY.list", "ProxySites", "ProxyIp"),
        RouteSpec("DIRECT.list", "DirectSites", "DirectIp")
    )

val outputDirectory = Path.of("rules")
Files.createDirectories(outputDirectory)

specs.forEach { spec ->
    val siteCategories = jsonArray(routing, spec.siteKey).map { categoryName(it, "geosite") }
    val ipCategories = jsonArray(routing, spec.ipKey).map { categoryName(it, "geoip") }
    val rules =
        buildList {
            siteCategories.forEach { addAll(geositeRules(it)) }
            ipCategories.forEach { addAll(geoipRules(it)) }
        }.distinct().sorted()

    check(rules.isNotEmpty()) { "Список ${spec.fileName} получился пустым" }

    val content =
        buildString {
            appendLine("# Автоматически сгенерировано из RoscomVPN")
            appendLine("# LastUpdated: $lastUpdated")
            appendLine("# Geosite: ${siteCategories.joinToString()}")
            appendLine("# GeoIP: ${ipCategories.joinToString()}")
            rules.forEach { appendLine(it) }
        }

    Files.writeString(outputDirectory.resolve(spec.fileName), content)
    println("${spec.fileName}: ${rules.size} правил")
}
