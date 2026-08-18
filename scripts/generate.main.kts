#!/usr/bin/env kotlin

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class RouteSpec(
    val fileName: String,
    val siteKey: String,
    val ipKey: String,
    val minimumRuleCount: Int
)

val routingUrl =
    "https://raw.githubusercontent.com/hydraponique/roscomvpn-routing/main/HAPP/DEFAULT.JSON"

val httpClient =
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

val downloadCache = mutableMapOf<String, String>()

fun download(url: String): String {
    downloadCache[url]?.let { return it }
    var lastFailure = "неизвестная ошибка"

    repeat(4) { attempt ->
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("User-Agent", "shadowrocket-roscomvpn-routing")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()
        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                val body = response.body()
                check(body.isNotBlank()) { "Источник $url вернул пустой ответ" }
                check(body.length <= 20_000_000) { "Источник $url превышает лимит 20 МБ" }
                downloadCache[url] = body
                return body
            }

            lastFailure = "HTTP ${response.statusCode()}"
            check(response.statusCode() == 429 || response.statusCode() in 500..599) {
                "Не удалось скачать $url: $lastFailure"
            }
        } catch (error: IOException) {
            lastFailure = error.message ?: error.javaClass.simpleName
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }

        if (attempt < 3) {
            Thread.sleep(2_000L shl attempt)
        }
    }

    error("Не удалось скачать $url после 4 попыток: $lastFailure")
}

sealed interface JsonValue

data class JsonStringValue(val value: String) : JsonValue

data class JsonArrayValue(val values: List<JsonValue>) : JsonValue

data class JsonObjectValue(val values: Map<String, JsonValue>) : JsonValue

data class JsonLiteralValue(val value: String) : JsonValue

class StrictJsonParser(private val source: String) {
    private var index = 0

    fun parseDocument(): JsonObjectValue {
        val value = parseValue()
        skipWhitespace()
        check(index == source.length) {
            "После JSON обнаружены лишние данные в позиции $index"
        }
        return value as? JsonObjectValue
            ?: error("Корневое значение DEFAULT.JSON должно быть объектом")
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        check(index < source.length) { "Неожиданный конец JSON" }
        return when (source[index]) {
            '"' -> JsonStringValue(parseString())
            '[' -> parseArray()
            '{' -> parseObject()
            else -> parseLiteral()
        }
    }

    private fun parseObject(): JsonObjectValue {
        expect('{')
        skipWhitespace()
        val values = linkedMapOf<String, JsonValue>()
        if (consume('}')) {
            return JsonObjectValue(values)
        }
        while (true) {
            check(index < source.length && source[index] == '"') {
                "В JSON-объекте ожидался строковый ключ в позиции $index"
            }
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            check(values.put(key, value) == null) { "В JSON повторяется ключ $key" }
            skipWhitespace()
            if (consume('}')) {
                return JsonObjectValue(values)
            }
            expect(',')
            skipWhitespace()
            check(index < source.length && source[index] != '}') {
                "В JSON-объекте обнаружена завершающая запятая"
            }
        }
    }

    private fun parseArray(): JsonArrayValue {
        expect('[')
        skipWhitespace()
        val values = mutableListOf<JsonValue>()
        if (consume(']')) {
            return JsonArrayValue(values)
        }
        while (true) {
            values.add(parseValue())
            skipWhitespace()
            if (consume(']')) {
                return JsonArrayValue(values)
            }
            expect(',')
            skipWhitespace()
            check(index < source.length && source[index] != ']') {
                "В JSON-массиве обнаружена завершающая запятая"
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        return buildString {
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return@buildString
                    character == '\\' -> appendEscapedCharacter()
                    character.code < 0x20 -> error("Управляющий символ в JSON-строке")
                    else -> append(character)
                }
            }
            error("JSON-строка не закрыта")
        }
    }

    private fun StringBuilder.appendEscapedCharacter() {
        check(index < source.length) { "JSON escape-последовательность не завершена" }
        when (val escaped = source[index++]) {
            '"' -> append('"')
            '\\' -> append('\\')
            '/' -> append('/')
            'b' -> append('\b')
            'f' -> append('\u000C')
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            'u' -> append(parseUnicodeEscape())
            else -> error("Некорректная JSON escape-последовательность: \\$escaped")
        }
    }

    private fun parseUnicodeEscape(): Char {
        check(index + 4 <= source.length) {
            "Unicode escape-последовательность не завершена"
        }
        val hexadecimal = source.substring(index, index + 4)
        check(hexadecimal.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Некорректная Unicode escape-последовательность: $hexadecimal"
        }
        index += 4
        return hexadecimal.toInt(16).toChar()
    }

    private fun parseLiteral(): JsonLiteralValue {
        val start = index
        while (index < source.length && source[index] !in setOf(',', ']', '}', ' ', '\t', '\r', '\n')) {
            index++
        }
        val literal = source.substring(start, index)
        val numberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        check(literal in setOf("true", "false", "null") || numberPattern.matches(literal)) {
            "Некорректный JSON literal в позиции $start: $literal"
        }
        return JsonLiteralValue(literal)
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in setOf(' ', '\t', '\r', '\n')) {
            index++
        }
    }

    private fun consume(expected: Char): Boolean {
        if (index >= source.length || source[index] != expected) {
            return false
        }
        index++
        return true
    }

    private fun expect(expected: Char) {
        check(consume(expected)) { "В позиции $index ожидался символ $expected" }
    }
}

fun jsonString(json: JsonObjectValue, key: String): String {
    val value = json.values[key] ?: error("В DEFAULT.JSON отсутствует поле $key")
    return (value as? JsonStringValue)?.value ?: error("Поле $key должно быть строкой")
}

fun jsonArray(json: JsonObjectValue, key: String): List<String> {
    val array = json.values[key] as? JsonArrayValue ?: error("Поле $key должно быть массивом")
    return array.values.mapIndexed { index, value ->
        (value as? JsonStringValue)?.value
            ?: error("Элемент $index массива $key должен быть строкой")
    }
}

fun jsonStringMap(json: JsonObjectValue, key: String): Map<String, String> {
    val objectValue =
        json.values[key] as? JsonObjectValue
            ?: error("Поле $key должно быть объектом")
    return objectValue.values.mapValues { (nestedKey, value) ->
        (value as? JsonStringValue)?.value
            ?: error("Значение $nestedKey объекта $key должно быть строкой")
    }
}

fun pinnedRef(
    sourceUrl: String,
    repository: String,
    assetPath: String,
    fieldName: String
): String {
    val match =
        Regex(
            "^https://cdn\\.jsdelivr\\.net/gh/${Regex.escape(repository)}@([^/]+)/" +
                "${Regex.escape(assetPath)}$"
        ).matchEntire(sourceUrl)
            ?: error("$fieldName должен ссылаться на pinned jsDelivr asset $repository/$assetPath")
    val ref = match.groupValues[1]
    require(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}").matches(ref)) {
        "Некорректный ref в $fieldName: $ref"
    }
    require(ref !in setOf("HEAD", "main", "master")) { "$fieldName содержит mutable ref: $ref" }
    return ref
}

fun validateCategory(category: String): String {
    require(category.length in 1..128 && Regex("[a-z0-9][a-z0-9._-]*").matches(category)) {
        "Некорректная категория: $category"
    }
    require(".." !in category) { "Некорректная категория: $category" }
    return category
}

fun validateDomain(domain: String, context: String): String {
    require(domain.length in 1..253 && domain == domain.trim()) {
        "Некорректный домен в $context: $domain"
    }
    require(!domain.startsWith('.') && !domain.endsWith('.') && ".." !in domain) {
        "Некорректный домен в $context: $domain"
    }
    domain.split('.').forEach { label ->
        require(label.length in 1..63) { "Некорректная метка домена в $context: $domain" }
        require(label.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Недопустимый символ домена в $context: $domain"
        }
        require(!label.startsWith('-') && !label.endsWith('-')) {
            "Некорректная метка домена в $context: $domain"
        }
    }
    return domain
}

fun validateDomainKeyword(keyword: String, context: String): String {
    require(keyword.isNotBlank() && keyword.none { it == ',' || it.isWhitespace() || it.isISOControl() }) {
        "Некорректный DOMAIN-KEYWORD в $context: $keyword"
    }
    return keyword
}

fun isValidIpv4(address: String): Boolean {
    val parts = address.split('.')
    return parts.size == 4 &&
        parts.all { part ->
            val number = part.toIntOrNull()
            part.isNotEmpty() &&
                part.all { it.isDigit() } &&
                (part == "0" || !part.startsWith('0')) &&
                number != null &&
                number in 0..255
        }
}

fun isValidIpv6(address: String): Boolean {
    val hasOnlyAddressCharacters =
        address.isNotEmpty() && address.none { character ->
            !character.isDigit() && character.lowercaseChar() !in 'a'..'f' && character !in setOf(':', '.')
        }
    return hasOnlyAddressCharacters &&
        ':' in address &&
        runCatching { InetAddress.getByName(address) }.getOrNull() is Inet6Address
}

fun cidrRule(cidr: String, category: String): String {
    val parts = cidr.split('/')
    require(parts.size == 2 && parts[1].isNotEmpty() && parts[1].all { it.isDigit() }) {
        "Некорректный CIDR в $category: $cidr"
    }
    val address = parts[0]
    val prefix = parts[1].toIntOrNull() ?: error("Некорректный CIDR в $category: $cidr")
    return if (':' in address) {
        require(isValidIpv6(address) && prefix in 0..128) { "Некорректный IPv6 CIDR в $category: $cidr" }
        "IP-CIDR6,$cidr,no-resolve"
    } else {
        require(isValidIpv4(address) && prefix in 0..32) { "Некорректный IPv4 CIDR в $category: $cidr" }
        "IP-CIDR,$cidr,no-resolve"
    }
}

fun dnsScopesOverlap(first: String, second: String): Boolean =
    first == second || first.endsWith(".$second") || second.endsWith(".$first")

fun alwaysRealPatternOverlapsZone(pattern: String, zone: String): Boolean {
    val normalizedPattern = pattern.lowercase().trimEnd('.')
    val normalizedZone = zone.lowercase().trimEnd('.')
    val lastWildcardIndex = maxOf(normalizedPattern.lastIndexOf('*'), normalizedPattern.lastIndexOf('?'))
    val fixedSuffix =
        if (lastWildcardIndex >= 0) {
            normalizedPattern.substring(lastWildcardIndex + 1).trimStart('.')
        } else {
            normalizedPattern
        }
    return fixedSuffix.isEmpty() || dnsScopesOverlap(fixedSuffix, normalizedZone)
}

fun replaceUniqueMarker(
    template: String,
    marker: String,
    replacement: String,
    templatePath: Path
): String {
    val markerIndex = template.indexOf(marker)
    check(markerIndex >= 0 && markerIndex == template.lastIndexOf(marker)) {
        "В ${templatePath.fileName} отсутствует единственный маркер $marker"
    }
    return template.replace(marker, replacement)
}

fun normalizedLines(text: String): List<String> =
    text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()

fun existingRuleCount(path: Path): Int? =
    if (Files.isRegularFile(path)) {
        Files
            .readString(path)
            .lineSequence()
            .count { line -> line.isNotBlank() && !line.startsWith("#") }
    } else {
        null
    }

fun regexRule(pattern: String, category: String): List<String> =
    when (pattern) {
        "^github-production-release-asset-[0-9a-zA-Z]{6}\\.s3\\.amazonaws\\.com\$" ->
            listOf("DOMAIN-WILDCARD,github-production-release-asset-??????.s3.amazonaws.com")
        "^[a-z]([a-z0-9-]{0,61}[a-z0-9])?\$" -> {
            check(category == "private") { "Неожиданное односегментное regexp в $category" }
            emptyList()
        }
        else -> error("Неподдерживаемое regexp в $category: $pattern")
    }

fun geositeRules(
    category: String,
    geositeBaseUrl: String,
    visited: MutableSet<String> = mutableSetOf()
): List<String> {
    validateCategory(category)
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
                    geositeRules(line.removePrefix("include:"), geositeBaseUrl, visited)
                line.startsWith("domain:") ->
                    listOf("DOMAIN-SUFFIX,${validateDomain(line.removePrefix("domain:"), category)}")
                line.startsWith("full:") ->
                    listOf("DOMAIN,${validateDomain(line.removePrefix("full:"), category)}")
                line.startsWith("keyword:") ->
                    listOf("DOMAIN-KEYWORD,${validateDomainKeyword(line.removePrefix("keyword:"), category)}")
                line.startsWith("regexp:") -> {
                    val pattern = line.removePrefix("regexp:")
                    regexRule(pattern, category)
                }
                ":" !in line -> listOf("DOMAIN-SUFFIX,${validateDomain(line, category)}")
                else -> error("Неподдерживаемая строка в $category: $line")
            }
        }

    visited.remove(category)
    return rules
}

fun geoipRules(category: String, geoipBaseUrl: String): List<String> {
    validateCategory(category)
    return normalizedLines(download("$geoipBaseUrl/$category.txt")).map { cidr -> cidrRule(cidr, category) }
}

fun alwaysRealIpPatterns(category: String, geositeBaseUrl: String): List<String> =
    geositeRules(category, geositeBaseUrl)
        .flatMap { rule ->
            val separatorIndex = rule.indexOf(',')
            check(separatorIndex > 0) { "Некорректное доменное правило в $category: $rule" }

            val type = rule.substring(0, separatorIndex)
            val value = rule.substring(separatorIndex + 1)
            when (type) {
                "DOMAIN" -> listOf(value)
                "DOMAIN-SUFFIX" -> listOf(value, "*.$value")
                "DOMAIN-WILDCARD" -> listOf(value)
                else -> error("Нельзя преобразовать $rule в always-real-ip")
            }
        }
        .distinct()
        .sorted()

fun categoryName(reference: String, prefix: String): String {
    val expectedPrefix = "$prefix:"
    require(reference.startsWith(expectedPrefix)) {
        "Ожидалась ссылка $expectedPrefix, получено: $reference"
    }
    return validateCategory(reference.removePrefix(expectedPrefix))
}

val routing = StrictJsonParser(download(routingUrl)).parseDocument()
val lastUpdated = jsonString(routing, "LastUpdated")
check(lastUpdated.isNotEmpty() && lastUpdated.all { it.isDigit() }) {
    "LastUpdated должен содержать Unix timestamp: $lastUpdated"
}
check(jsonString(routing, "GlobalProxy") == "true") {
    "Поддерживается только GlobalProxy=true"
}
check(jsonString(routing, "RouteOrder") == "block-proxy-direct") {
    "Поддерживается только RouteOrder=block-proxy-direct"
}

val geositeRef =
    pinnedRef(
        sourceUrl = jsonString(routing, "Geositeurl"),
        repository = "hydraponique/roscomvpn-geosite",
        assetPath = "release/geosite.dat",
        fieldName = "Geositeurl"
    )
val geoipRef =
    pinnedRef(
        sourceUrl = jsonString(routing, "Geoipurl"),
        repository = "hydraponique/roscomvpn-geoip",
        assetPath = "release/geoip.dat",
        fieldName = "Geoipurl"
    )
val geositeBaseUrl =
    "https://cdn.jsdelivr.net/gh/hydraponique/roscomvpn-geosite@$geositeRef/data"
val geoipBaseUrl =
    "https://cdn.jsdelivr.net/gh/hydraponique/roscomvpn-geoip@$geoipRef/release/text"

val dnsHosts = sortedMapOf<String, String>()
jsonStringMap(routing, "DnsHosts").forEach { (host, address) ->
    val normalizedHost = validateDomain(host, "DnsHosts").lowercase()
    require(isValidIpv4(address)) {
        "DnsHosts поддерживает только корректный IPv4: $host = $address"
    }
    check(dnsHosts.put(normalizedHost, address) == null) {
        "В DnsHosts повторяется домен без учёта регистра: $host"
    }
}
check(dnsHosts.isNotEmpty()) { "DnsHosts неожиданно пуст" }

val specs =
    listOf(
        RouteSpec(
            fileName = "BLOCK.list",
            siteKey = "BlockSites",
            ipKey = "BlockIp",
            minimumRuleCount = 100
        ),
        RouteSpec(
            fileName = "PROXY.list",
            siteKey = "ProxySites",
            ipKey = "ProxyIp",
            minimumRuleCount = 50
        ),
        RouteSpec(
            fileName = "DIRECT.list",
            siteKey = "DirectSites",
            ipKey = "DirectIp",
            minimumRuleCount = 1_000
        )
    )

val outputDirectory = Path.of("rules")
val generatedFiles = linkedMapOf<Path, String>()
val generatedRuleCounts = linkedMapOf<String, Int>()

specs.forEach { spec ->
    val siteCategories = jsonArray(routing, spec.siteKey).map { categoryName(it, "geosite") }
    val ipCategories = jsonArray(routing, spec.ipKey).map { categoryName(it, "geoip") }
    val rules =
        buildList {
            siteCategories.forEach { addAll(geositeRules(it, geositeBaseUrl)) }
            ipCategories.forEach { addAll(geoipRules(it, geoipBaseUrl)) }
        }.distinct().sorted()

    check(rules.size >= spec.minimumRuleCount) {
        """
        Список ${spec.fileName} содержит только ${rules.size} правил.
        Минимум: ${spec.minimumRuleCount}
        """.trimIndent()
    }
    val previousRuleCount = existingRuleCount(outputDirectory.resolve(spec.fileName))
    if (previousRuleCount != null && previousRuleCount >= spec.minimumRuleCount) {
        check(rules.size.toLong() * 100 >= previousRuleCount.toLong() * 70) {
            "Список ${spec.fileName} резко уменьшился: $previousRuleCount -> ${rules.size}"
        }
    }

    val content =
        buildString {
            appendLine("# Автоматически сгенерировано из RoscomVPN")
            appendLine("# LastUpdated: $lastUpdated")
            appendLine("# Geosite ref: $geositeRef")
            appendLine("# GeoIP ref: $geoipRef")
            appendLine("# Geosite: ${siteCategories.joinToString()}")
            appendLine("# GeoIP: ${ipCategories.joinToString()}")
            rules.forEach { appendLine(it) }
        }

    generatedFiles[outputDirectory.resolve(spec.fileName)] = content
    generatedRuleCounts[spec.fileName] = rules.size
}

val directSiteCategories = jsonArray(routing, "DirectSites").map { categoryName(it, "geosite") }
check("whitelist" in directSiteCategories) {
    "В DirectSites отсутствует обязательная категория geosite:whitelist"
}

val ipCheckProxyDomains =
    setOf(
        "2ip.ru",
        "checkip.amazonaws.com",
        "ifconfig.me",
        "ip.sb",
        "ipapi.is",
        "ipify.org",
        "iplocate.io",
        "showip.net"
    )
val protectedAlwaysRealIpZones = ipCheckProxyDomains + "mdb.yandexcloud.net"
dnsHosts.keys.forEach { host ->
    check(protectedAlwaysRealIpZones.none { zone -> alwaysRealPatternOverlapsZone(host, zone) }) {
        "DnsHosts-домен $host конфликтует с защищённой зоной always-real-ip"
    }
}
val alwaysRealIpCandidates =
    (alwaysRealIpPatterns("whitelist", geositeBaseUrl) + dnsHosts.keys)
        .distinct()
        .sorted()
val shadowrocketAlwaysRealIpPatterns =
    alwaysRealIpCandidates.filterNot { pattern ->
        protectedAlwaysRealIpZones.any { zone -> alwaysRealPatternOverlapsZone(pattern, zone) }
    }
check(shadowrocketAlwaysRealIpPatterns.isNotEmpty()) { "Список always-real-ip получился пустым" }
check("*.netmonet.co" in shadowrocketAlwaysRealIpPatterns) {
    "В geosite:whitelist отсутствует ожидаемый домен netmonet.co"
}
protectedAlwaysRealIpZones.forEach { zone ->
    check(shadowrocketAlwaysRealIpPatterns.none { pattern -> alwaysRealPatternOverlapsZone(pattern, zone) }) {
        "$zone не должен отключать Fake-IP через always-real-ip"
    }
}
dnsHosts.keys.forEach { host ->
    check(host in shadowrocketAlwaysRealIpPatterns) {
        "DnsHosts-домен $host отсутствует в always-real-ip"
    }
}

val templatePath = Path.of("shadowrocket.template.conf")
val alwaysRealIpMarker = "{{ROSCOMVPN_WHITELIST_ALWAYS_REAL_IP}}"
val dnsHostsSectionMarker = "{{ROSCOMVPN_DNS_HOSTS_SECTION}}"
val configTemplate = Files.readString(templatePath)
val templateRuleLines = configTemplate.lineSequence().map { it.trim() }.toSet()
ipCheckProxyDomains.forEach { domain ->
    check("DOMAIN-SUFFIX,$domain,PROXY" in templateRuleLines) {
        "В ${templatePath.fileName} нет PROXY-правила для $domain"
    }
}

val dnsHostsSection =
    buildString {
        appendLine("[Host]")
        dnsHosts.forEach { (host, address) -> appendLine("$host = $address") }
    }.trimEnd()
val configWithAlwaysRealIp =
    replaceUniqueMarker(
        template = configTemplate,
        marker = alwaysRealIpMarker,
        replacement = shadowrocketAlwaysRealIpPatterns.joinToString(", "),
        templatePath = templatePath
    )
val shadowrocketConfig =
    replaceUniqueMarker(
        template = configWithAlwaysRealIp,
        marker = dnsHostsSectionMarker,
        replacement = dnsHostsSection,
        templatePath = templatePath
    )
val generatedConfigLines = shadowrocketConfig.lineSequence().map { it.trim() }.toSet()
check("{{ROSCOMVPN_" !in shadowrocketConfig) {
    "В shadowrocket.conf остался незаменённый marker"
}
check("dns-direct-fallback-proxy = false" in generatedConfigLines) {
    "DIRECT DNS не должен переключаться на proxy fallback"
}
check("DOMAIN-SUFFIX,mdb.yandexcloud.net,DIRECT" in generatedConfigLines) {
    "В shadowrocket.conf отсутствует раннее DIRECT-правило Yandex MDB"
}
generatedFiles[Path.of("shadowrocket.conf")] = shadowrocketConfig

Files.createDirectories(outputDirectory)
generatedFiles.forEach { (path, content) -> Files.writeString(path, content) }
generatedRuleCounts.forEach { (fileName, count) -> println("$fileName: $count правил") }
println("shadowrocket.conf: ${shadowrocketAlwaysRealIpPatterns.size} шаблонов always-real-ip")
println("shadowrocket.conf: ${dnsHosts.size} записей DnsHosts")
